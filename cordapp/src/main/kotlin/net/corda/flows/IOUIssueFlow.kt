package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.IOUContract
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.states.IOUState

@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(private val state: IOUState) : FlowLogic<SignedTransaction>() {
//    @Suspendable
//    override fun call(): SignedTransaction {
//        val notary = serviceHub.networkMapCache.notaryIdentities.first()
//        val issueCommand = Command(IOUContract.Commands.Issue(), state.participants.map { it.owningKey })
//        val transaction = TransactionBuilder(notary = notary)
//        transaction.addOutputState(state, IOUContract.IOU_CONTRACT_ID)
//        transaction.addCommand(issueCommand)
//        transaction.verify(serviceHub)
//        val singleSignedTransaction = serviceHub.signInitialTransaction(transaction)
//        val sessions = (state.participants - ourIdentity).map { initiateFlow(it) }.toSet()
//        val allSignedTransaction = subFlow(CollectSignaturesFlow(singleSignedTransaction, sessions))
//        subFlow(FinalityFlow(allSignedTransaction))
//        return allSignedTransaction
//    }

    @Suspendable
    override fun call(): SignedTransaction {
        val stx =  collectSignatures(verifyAndSign(transaction()))
        return subFlow(FinalityFlow(stx))
    }

    @Suspendable
    private fun collectSignatures(transaction: SignedTransaction): SignedTransaction {
        val sessions = (state.participants - ourIdentity).map { initiateFlow(it) }.toSet()
        return subFlow(CollectSignaturesFlow(transaction, sessions))
    }

    private fun verifyAndSign(transaction: TransactionBuilder): SignedTransaction {
        transaction.verify(serviceHub)
        return serviceHub.signInitialTransaction(transaction)
    }

    private fun transaction() = TransactionBuilder(notary()).apply {
        addOutputState(state, IOUContract.IOU_CONTRACT_ID)
        addCommand(Command(IOUContract.Commands.Issue(), state.participants.map { it.owningKey }))
    }

    private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()
}

@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an IOU transaction" using (output is IOUState)
                }
            }
        }
        subFlow(signedTransactionFlow)
    }
}