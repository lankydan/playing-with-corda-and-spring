package net.corda.server.controllers

import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.Amount
import net.corda.core.messaging.startFlow
import net.corda.flows.IssueCashFlow
import net.corda.server.NodeRPCConnection
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping("/cash")
class CashController(rpc: NodeRPCConnection) {

    private val proxy = rpc.proxy
    private val mapper = JacksonSupport.createDefaultMapper(proxy)

    @PostMapping("/issue")
    fun issue(@RequestParam("amount") amount: Int,
              @RequestParam("currency") currency: String) : ResponseEntity<String> {
        return try {
            val currencyAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))
            val flowProgressHandle = proxy.startFlow(::IssueCashFlow, currencyAmount)
            val result = flowProgressHandle.returnValue.get()
            ResponseEntity.ok().body(mapper.writeValueAsString(result))
        } catch(e: Exception) {
            ResponseEntity.badRequest().body(e.message)
        }
    }
}