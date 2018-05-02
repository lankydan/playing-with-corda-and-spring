package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.confidential.IdentitySyncFlow
import net.corda.contracts.IOUContract
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalances
import net.corda.finance.flows.CashIssueFlow
import net.corda.states.IOUState
import java.util.*

/**
 * This is the flow which handles the (partial) settlement of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled vy the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUSettleFlow(val linearId: UniqueIdentifier, val amount: Amount<Currency>) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val stateAndRef = stateAndRef()
        val state = stateAndRef.state.data
        validateIdentity(state)
        validateCash(amount)
        val counterparty = state.lender
        val transaction = TransactionBuilder(notary = stateAndRef.state.notary)

        val (_, keys) = Cash.generateSpend(serviceHub, transaction, amount, ourIdentityAndCert, counterparty)

        transaction.addInputState(stateAndRef)
        val output = state.pay(amount)
        if (output.paid < output.amount) {
            transaction.addOutputState(output, IOUContract.IOU_CONTRACT_ID)
        }
        transaction.addCommand(Command(IOUContract.Commands.Settle(), state.participants.map { it.owningKey }))
        transaction.verify(serviceHub)

        // We need to sign transaction with all keys referred from Cash input states + our public key
        val keysToSign = (keys.toSet() + ourIdentity.owningKey).toList()
        val singleSignedTransaction = serviceHub.signInitialTransaction(transaction, keysToSign)
        // Initialising session with other party
        val session = initiateFlow(counterparty)

        // Sending other party our identities so they are aware of anonymous public keys
        subFlow(IdentitySyncFlow.Send(session, singleSignedTransaction.tx))

        // Step 9. Collecting missing signatures
        val signedByAllPartiesTransaction = subFlow(CollectSignaturesFlow(singleSignedTransaction, listOf(session), myOptionalKeys = keysToSign))

        return subFlow(FinalityFlow(signedByAllPartiesTransaction))

    }

    private fun stateAndRef(): StateAndRef<IOUState> {
        val query = queryCriteria()
        return serviceHub.vaultService.queryBy<IOUState>(query)
                .states
                .single()
    }

    private fun queryCriteria(): QueryCriteria.LinearStateQueryCriteria {
        return if (linearId.externalId == null) QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        else QueryCriteria.LinearStateQueryCriteria(externalId = listOf(linearId.externalId!!))
    }

    private fun validateIdentity(state: IOUState) {
        requireThat {
            "The borrower must be initiating this flow." using (state.borrower == ourIdentity)
        }
    }

    private fun validateCash(amount: Amount<Currency>) {
        val cash = serviceHub.getCashBalances()[amount.token]
        requireThat {
            "Borrower has no ${amount.token} to settle." using (cash != null && cash.quantity > 0)
            "Borrower has only ${cash?.toDecimal()} ${cash?.token} but needs ${amount.toDecimal()} ${amount.token} to settle." using (cash!! >= amount)
        }
    }
}

/**
 * This is the flow which signs IOU settlements.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUSettleFlow::class)
class IOUSettleFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(IdentitySyncFlow.Receive(flowSession))
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
            }
        }
        subFlow(signedTransactionFlow)
    }
}

@InitiatingFlow
@StartableByRPC
/**
 * Self issues the calling node an amount of cash in the desired currency.
 * Only used for demo/sample/training purposes!
 */
class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {
    @Suspendable
    override fun call(): Cash.State {
        /** Create the cash issue command. */
        val issueRef = OpaqueBytes.of(0)
        /** Note: ongoing work to support multiple notary identities is still in progress. */
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        /** Create the cash issuance transaction. */
        val cashIssueTransaction = subFlow(CashIssueFlow(amount, issueRef, notary))
        /** Return the cash output. */
        return cashIssueTransaction.stx.tx.outputs.single().data as Cash.State
    }
}