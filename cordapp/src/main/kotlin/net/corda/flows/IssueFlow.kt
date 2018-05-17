package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.IOUContract
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import net.corda.core.utilities.loggerFor
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
        return collectSignatures(verifyAndSign(transaction()))
    }

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
class IOUIssueFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {
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

///**
// * This is the flow which handles issuance of new IOUs on the ledger.
// * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
// * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
// * The flow returns the [SignedTransaction] that was committed to the ledger.
// */
//@InitiatingFlow
//@StartableByRPC
//class IOUIssueFlow(private val state: IOUState) : FlowLogic<SignedTransaction>() {
//
//    private companion object {
//        private val log = loggerFor<IOUIssueFlow>()
//
//        object ID_OTHER_NODES : Step("Identifying other nodes on the network")
//        object SENDING_AND_RECEIVING_DATA : Step("Sending data between parties")
//        object EXTRACTING_VAULT_STATES : Step("Extracting states from the vault")
//        object OTHER_TX_COMPONENTS : Step("Gathering a transaction's other components")
//        object TX_BUILDING : Step("Building a transaction")
//        object TX_SIGNING : Step("Signing a transaction")
//        object TX_VERIFICATION : Step("Verifying a transaction")
//        object SIGS_GATHERING : Step("Gathering a transaction's signatures") {
//            // Wiring up a child progress tracker allows us to see the
//            // subflow's progress steps in our flow's progress tracker.
//            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
//        }
//
//        object VERIFYING_SIGS : Step("Verifying a transaction's signatures")
//        object FINALISATION : Step("Finalising a transaction") {
//            override fun childProgressTracker() = FinalityFlow.tracker()
//        }
//
//        fun tracker() = ProgressTracker(
//                ID_OTHER_NODES,
//                SENDING_AND_RECEIVING_DATA,
//                EXTRACTING_VAULT_STATES,
//                OTHER_TX_COMPONENTS,
//                TX_BUILDING,
//                TX_SIGNING,
//                TX_VERIFICATION,
//                SIGS_GATHERING,
//                VERIFYING_SIGS,
//                FINALISATION
//        )
//    }
//
//    override val progressTracker = tracker()
//
//    @Suspendable
//    override fun call(): SignedTransaction {
//
//        // Step 1. Get a reference to the notary service on our network and our key pair.
//        // Note: ongoing work to support multiple notary identities is still in progress.
//        val notary = serviceHub.networkMapCache.notaryIdentities.first()
//
//        // Step 2. Create a new issue command.
//        // Remember that a command is a CommandData object and a list of CompositeKeys
//        val issueCommand = Command(IOUContract.Commands.Issue(), state.participants.map { it.owningKey })
//
//        // Step 3. Create a new TransactionBuilder object.
//        progressTracker.currentStep = TX_BUILDING
//        val transaction = TransactionBuilder(notary = notary)
//
//        // Step 4. Add the iou as an output state, as well as a command to the transaction builder.
//        transaction.addOutputState(state, IOUContract.IOU_CONTRACT_ID.toString())
//        transaction.addCommand(issueCommand)
//
//        // Step 5. Verify and sign it with our KeyPair.
//        progressTracker.currentStep = TX_VERIFICATION
//        transaction.verify(serviceHub)
//
//        progressTracker.currentStep = TX_SIGNING
//        log.info("Signing transaction")
//        val singleSignedTransaction = serviceHub.signInitialTransaction(transaction)
//        log.info("Transaction [${singleSignedTransaction.id} signed")
//
//        progressTracker.currentStep = ID_OTHER_NODES
//        val sessions = (state.participants - ourIdentity).map { initiateFlow(it) }.toSet()
//
//        // Step 6. Collect the other party's signature using the SignTransactionFlow.
//        progressTracker.currentStep = SIGS_GATHERING
//        log.info("Collecting counterparty signatures for transaction [${singleSignedTransaction.id}]")
//        // the progress tracker should output messages relating to collection of signatures and their verifications when received?
////        val allSignedTransaction = subFlow(CollectSignaturesFlow(singleSignedTransaction, sessions, progressTracker))
//        val allSignedTransaction = subFlow(CollectSignaturesFlow(singleSignedTransaction, sessions))
//
//        progressTracker.currentStep = FINALISATION
//        log.info("Signatures collected for transaction [${allSignedTransaction.id}]")
//
//
//        // Step 7. Assuming no exceptions, we can now finalise the transaction. Sends to notary and commits if valid
//        subFlow(FinalityFlow(allSignedTransaction))
//        log.info("Transaction [${allSignedTransaction.id}] committed")
//
//        return allSignedTransaction
//    }
//}
//
///**
// * This is the flow which signs IOU issuances.
// * The signing is handled by the [SignTransactionFlow].
// */
//@InitiatedBy(IOUIssueFlow::class)
//class IOUIssueFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {
//
//    private companion object {
//        private val log = loggerFor<IOUIssueFlowResponder>()
//    }
//
//    @Suspendable
//    override fun call() {
//        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
//            override fun checkTransaction(stx: SignedTransaction) {
//                log.info("Received transaction [${stx.id}]")
//                requireThat {
//                    val output = stx.tx.outputs.single().data
//                    "This must be an IOU transaction" using (output is IOUState)
//                }
//            }
//        }
//        subFlow(signedTransactionFlow)
//    }
//}