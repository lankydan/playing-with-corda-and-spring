package net.corda.server.controllers

import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.loggerFor
import net.corda.flows.IOUIssueFlow
import net.corda.server.NodeRPCConnection
import net.corda.states.IOUState
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.util.*

@RestController
@RequestMapping("/IOU")
class IOUController(rpc: NodeRPCConnection) {

		private val proxy = rpc.proxy
		private val mapper = JacksonSupport.createDefaultMapper(proxy)

		companion object {
				private val log = loggerFor<IOUController>()
		}

//    @GetMapping("/all", produces = arrayOf(APPLICATION_JSON_VALUE))
//    fun all(): ResponseEntity<List<StateAndRef<ContractState>>> {
//        return ResponseEntity.ok(proxy.vaultQueryBy<IOUState>().states)
//    }

		@GetMapping("/all", produces = arrayOf(APPLICATION_JSON_VALUE))
		fun all(): ResponseEntity<String> {
				return ResponseEntity.ok(mapper.writeValueAsString(proxy.vaultQueryBy<IOUState>().states))
		}

		@PostMapping("/issue")
		fun issue(@RequestParam("amount") amount: Int,
							@RequestParam("currency") currency: String,
							@RequestParam("party") party: String): /*ResponseEntity<SignedTransaction>*/ ResponseEntity<String> {

				log.info("Received issue request for $currency $amount to $party")
				// Get party objects for myself and the counterparty.
				val me = proxy.nodeInfo().legalIdentities.first()
				val lender = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(party))
								?: throw IllegalArgumentException("Unknown party name.")
				// Create a new IOU state using the parameters given.
				return try {
						val state = IOUState(Amount(amount.toLong() * 100, Currency.getInstance(currency)), lender, me)
						// Start the IOUIssueFlow. We block and waits for the flow to return.
						val start = System.currentTimeMillis()
						log.info("Calling issue flow at ${LocalDateTime.now()}")
						val result = proxy.startTrackedFlow(::IOUIssueFlow, state).returnValue.get()
						// Return the response.
//            return ResponseEntity.ok()
//                    .body(result)
						val end = System.currentTimeMillis()
						log.info("Received response for committed transaction at ${LocalDateTime.now()}")
						log.info("Transaction time of ${start - end}")
						ResponseEntity.ok()
										.body(mapper.writeValueAsString(result))
						// For the purposes of this demo app, we do not differentiate by exception type.
				} catch (e: Exception) {
						ResponseEntity.badRequest()
										.build()
				}
		}
}