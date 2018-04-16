package net.corda.server

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
private open class Starter

fun main(args: Array<String>) {
    SpringApplication.run(Starter::class.java)
    LoggerFactory.getLogger(Starter::class.java).info("DOOOOO SOMETHING!!!!!!!!!!!!")
}