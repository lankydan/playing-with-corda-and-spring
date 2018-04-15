package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.IOUContract
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.loggerFor
import net.corda.states.IOUState

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(private val state: IOUState) : FlowLogic<SignedTransaction>() {

		private companion object {
				private val log = loggerFor<IOUIssueFlow>()
		}

		@Suspendable
		override fun call(): SignedTransaction {
				// Step 1. Get a reference to the notary service on our network and our key pair.
				// Note: ongoing work to support multiple notary identities is still in progress.
				val notary = serviceHub.networkMapCache.notaryIdentities.first()

				// Step 2. Create a new issue command.
				// Remember that a command is a CommandData object and a list of CompositeKeys
				val issueCommand = Command(IOUContract.Commands.Issue(), state.participants.map { it.owningKey })

				// Step 3. Create a new TransactionBuilder object.
				val transaction = TransactionBuilder(notary = notary)

				// Step 4. Add the iou as an output state, as well as a command to the transaction builder.
				transaction.addOutputState(state, IOUContract.IOU_CONTRACT_ID)
				transaction.addCommand(issueCommand)

				// Step 5. Verify and sign it with our KeyPair.
				log.info("Signing transaction")
				transaction.verify(serviceHub)
				val singleSignedTransaction = serviceHub.signInitialTransaction(transaction)
				log.info("Transaction [${singleSignedTransaction.id} signed")

				val sessions = (state.participants - ourIdentity).map { initiateFlow(it) }.toSet()
				// Step 6. Collect the other party's signature using the SignTransactionFlow.
				log.info("Collecting counterparty signatures for transaction [${singleSignedTransaction.id}]")
				val allSignedTransaction = subFlow(CollectSignaturesFlow(singleSignedTransaction, sessions))
				log.info("Signatures collected for transaction [${allSignedTransaction.id}]")
				// Step 7. Assuming no exceptions, we can now finalise the transaction.
				subFlow(FinalityFlow(allSignedTransaction))
				log.info("Transaction [${allSignedTransaction.id}] committed")

				return allSignedTransaction
		}
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {

		private companion object {
				private val log = loggerFor<IOUIssueFlowResponder>()
		}

		@Suspendable
		override fun call() {
				val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
						override fun checkTransaction(stx: SignedTransaction) {
								log.info("Received transaction [${stx.id}]")
								requireThat {
										val output = stx.tx.outputs.single().data
										"This must be an IOU transaction" using (output is IOUState)
								}
						}
				}
				subFlow(signedTransactionFlow)
		}
}