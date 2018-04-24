package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
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
import net.corda.finance.contracts.asset.PartyAndAmount
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
        val transaction = TransactionBuilder(notary = notary())

        Cash.generateSpend(serviceHub, transaction, listOf(PartyAndAmount(state.lender, amount)), ourIdentityAndCert)

        transaction.addInputState(stateAndRef)
        transaction.addOutputState(state.pay(amount), IOUContract.IOU_CONTRACT_ID)
        transaction.addCommand(Command(IOUContract.Commands.Settle(), state.participants.map { it.owningKey }))
        transaction.verify(serviceHub)
        val singleSignedTransaction =  serviceHub.signInitialTransaction(transaction)
        val sessions = (state.participants - ourIdentity).map { initiateFlow(it) }.toSet()
        val signedByAllPartiesTransaction = subFlow(CollectSignaturesFlow(singleSignedTransaction, sessions))
        return subFlow(FinalityFlow(signedByAllPartiesTransaction))
    }

    private fun stateAndRef(): StateAndRef<IOUState> {
        val query = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        return serviceHub.vaultService.queryBy<IOUState>(query)
                .states
                .single()
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
            "Borrower has only ${cash?.quantity} ${cash?.token} but needs ${amount.quantity} ${amount.token} to settle." using (cash!! >= amount)
        }
    }

    private fun notary(): Party {
        return serviceHub.networkMapCache.notaryIdentities.first()
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
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val outputStates = stx.tx.outputs.map { it.data::class.java.name }.toList()
                "There must be an IOU transaction." using (outputStates.contains(IOUState::class.java.name))
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