package com.juujarvis

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
class JuujarvisApplication

fun main(args: Array<String>) {
    runApplication<JuujarvisApplication>(*args)
}
