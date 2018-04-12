package net.corda.server.controllers

import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.vaultQueryBy
import net.corda.flows.IOUIssueFlow
import net.corda.server.NodeRPCConnection
import net.corda.states.IOUState
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/IOU")
class IOUController(rpc: NodeRPCConnection) {

		private val proxy = rpc.proxy

		companion object {
				private val logger = LoggerFactory.getLogger(RestController::class.java)
		}

		@GetMapping("all", produces = arrayOf(APPLICATION_JSON_VALUE))
		fun all(): ResponseEntity<List<StateAndRef<ContractState>>> {
				return ResponseEntity.ok(proxy.vaultQueryBy<IOUState>().states)
		}

		@PostMapping("issue")
		fun issue(@RequestParam("amount") amount: Int,
							@RequestParam("currency") currency: String,
							@RequestParam("party") party: String): ResponseEntity<StateAndRef<ContractState>> {
				// Get party objects for myself and the counterparty.
				val me = proxy.nodeInfo().legalIdentities.first()
				val lender = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(party))
								?: throw IllegalArgumentException("Unknown party name.")
				// Create a new IOU state using the parameters given.
				try {
						val state = IOUState(Amount(amount.toLong() * 100, Currency.getInstance(currency)), lender, me)
						// Start the IOUIssueFlow. We block and waits for the flow to return.
						val result = proxy.startTrackedFlow(::IOUIssueFlow, state).returnValue.get()
						// Return the response.
						return ResponseEntity.ok()
										.body(result)
						// For the purposes of this demo app, we do not differentiate by exception type.
				} catch (e: Exception) {
						return Response
										.status(Response.Status.BAD_REQUEST)
										.entity(e.message)
										.build()
				}
		}
}