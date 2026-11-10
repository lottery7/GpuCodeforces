package dev.gpucodeforces.backend

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore

@RestController
@RequestMapping("/api")
class ApiController(
    private val service: PlatformService,
    private val accounts: Accounts,
    private val db: Database,
    private val live: RuntimeSettings,
) {
    private val examples = Semaphore(16)

    @GetMapping("/session")
    fun session(request: HttpServletRequest) = service.user(accounts.requireUser(request))

    @PostMapping("/session")
    fun login(@RequestBody input: LoginInput, response: HttpServletResponse) =
        service.user(accounts.login(input, response))

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(request: HttpServletRequest, response: HttpServletResponse) = accounts.logout(request, response)

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody input: RegisterInput, response: HttpServletResponse) =
        service.user(accounts.register(input, response))

    @GetMapping("/users")
    fun users(
        @RequestParam(defaultValue = "") q: String, @RequestParam(defaultValue = "handle") sort: String,
        @RequestParam(defaultValue = "0") offset: Int, @RequestParam(defaultValue = "20") limit: Int,
    ) = service.users(q, sort, offset, limit)

    @GetMapping("/users/{id}")
    fun profile(@PathVariable id: String) = service.profile(id)

    @GetMapping("/problems")
    fun problems(@RequestParam params: Map<String, String>, request: HttpServletRequest) =
        service.problems(params, accounts.current(request))

    @GetMapping("/problems/{id}")
    fun problem(
        @PathVariable id: String,
        @RequestParam(defaultValue = "practice") context: String,
        request: HttpServletRequest
    ) =
        service.problem(id, context, accounts.current(request))

    @GetMapping("/problems/{id}/leaders")
    fun leaders(
        @PathVariable id: String,
        @RequestParam(defaultValue = "practice") context: String,
        request: HttpServletRequest
    ) =
        service.leaders(id, context, accounts.current(request))

    @PostMapping("/problems/{id}/example")
    fun example(
        @PathVariable id: String,
        @RequestParam(defaultValue = "practice") context: String,
        @RequestBody input: ExampleInput,
        request: HttpServletRequest,
    ): Map<String, Any> {
        val userId = accounts.requireUser(request)
        if (!examples.tryAcquire()) fail(503, "Слишком много проверок примеров. Попробуйте позже.")
        try {
            val submission =
                service.submit(SubmitInput(id, input.code, context.takeUnless { it == "practice" }), userId, true)
            val deadline = Instant.now().plusSeconds(live.current().values.exampleWaitSeconds)
            while (Instant.now() < deadline) {
                val s = db.submission(submission)
                if (s.status !in pendingStatuses) return mapOf(
                    "passed" to (s.status == "accepted"),
                    "diagnostic" to s.diagnostic
                )
                Thread.sleep(100)
            }
            fail(504, "Проверка примера не завершилась вовремя. Попробуйте позже.")
        } finally {
            examples.release()
        }
    }

    @GetMapping("/submissions")
    fun submissions(@RequestParam params: Map<String, String>, request: HttpServletRequest) =
        service.submissions(params, accounts.current(request))

    @PostMapping("/submissions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submit(@RequestBody input: SubmitInput, request: HttpServletRequest): Map<String, Any?> {
        val user = accounts.requireUser(request)
        return service.submissionDto(service.submit(input, user), user)
    }

    @GetMapping("/submissions/{id}")
    fun submission(@PathVariable id: UUID, request: HttpServletRequest) =
        service.submissionDto(id, accounts.current(request), true)

    @PostMapping("/submissions/{id}/recheck")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun recheck(@PathVariable id: UUID, request: HttpServletRequest): Map<String, Any?> {
        val user = accounts.requireUser(request)
        service.recheck(id, user)
        return service.submissionDto(id, user)
    }

    @GetMapping("/contests")
    fun contests(
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(required = false) phase: String?,
        @RequestParam(defaultValue = "false") mine: Boolean,
        request: HttpServletRequest,
    ) = service.contests(q, phase, mine, accounts.current(request))

    @PostMapping("/contests")
    @ResponseStatus(HttpStatus.CREATED)
    fun createContest(@RequestBody input: ContestInput, request: HttpServletRequest): Map<String, Any?> {
        val user = accounts.requireUser(request)
        return service.contestDto(service.saveContest(null, input, user), user, true)
    }

    @GetMapping("/contests/{id}")
    fun contest(@PathVariable id: UUID, request: HttpServletRequest) =
        service.contestDto(id, accounts.current(request), true)

    @PutMapping("/contests/{id}")
    fun updateContest(
        @PathVariable id: UUID,
        @RequestBody input: ContestInput,
        request: HttpServletRequest
    ): Map<String, Any?> {
        val user = accounts.requireUser(request)
        return service.contestDto(service.saveContest(id, input, user), user, true)
    }

    @PutMapping("/contests/{id}/registration")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun registerContest(@PathVariable id: UUID, request: HttpServletRequest) {
        service.register(id, accounts.requireUser(request))
    }

    @GetMapping("/contests/{id}/standings")
    fun standings(@PathVariable id: UUID) = service.standings(id)
}

@RestController
@RequestMapping("/api/internal/judges/{workerId}")
class JudgeController(private val judge: Judge) {
    @PostMapping("/results")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun result(
        @PathVariable workerId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody result: TestCallback,
    ) {
        judge.authenticate(workerId, authorization)
        judge.receive(workerId, result)
    }

    @PostMapping("/pong")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun pong(
        @PathVariable workerId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody input: PongInput,
    ) {
        judge.authenticate(workerId, authorization)
        judge.pong(workerId, input.nonce)
    }
}
