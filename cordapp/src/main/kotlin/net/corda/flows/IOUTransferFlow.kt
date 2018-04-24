package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.IOUContract
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.states.IOUState

/**
 * This is the flow which handles transfers of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled vy the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUTransferFlow(val linearId: UniqueIdentifier, val newLender: Party) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {

        val stateAndRef = stateAndRef()
        val input = stateAndRef.state.data
        validateLender(input)
        val output = input.withNewLender(newLender)
        val transaction = TransactionBuilder(notary())
        transaction.addInputState(stateAndRef)
        transaction.addOutputState(output, IOUContract.IOU_CONTRACT_ID)
        val participants = listOf(input.borrower, input.lender, newLender)
        transaction.addCommand(Command(IOUContract.Commands.Transfer(), participants.map { it.owningKey }))
        transaction.verify(serviceHub)
        val singleSignedTransaction = serviceHub.signInitialTransaction(transaction)
        val sessions = (participants.distinct() - ourIdentity).distinct().map { initiateFlow(it) }.toSet()
        val allSignedTransaction = subFlow(CollectSignaturesFlow(singleSignedTransaction, sessions))
        return subFlow(FinalityFlow(allSignedTransaction))
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

    private fun validateLender(state: IOUState) {
        requireThat {
            "Only the lender can start this flow" using (state.lender == ourIdentity)
        }
    }

    private fun notary(): Party {
        return serviceHub.networkMapCache.notaryIdentities.single()
    }
}

/**
 * This is the flow which signs IOU transfers.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUTransferFlow::class)
class IOUTransferFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }

        subFlow(signedTransactionFlow)
    }
}