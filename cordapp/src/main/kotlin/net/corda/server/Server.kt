package net.corda.server

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
private open class Starter

fun main(args: Array<String>) {
    SpringApplication.run(Starter::class.java)
}