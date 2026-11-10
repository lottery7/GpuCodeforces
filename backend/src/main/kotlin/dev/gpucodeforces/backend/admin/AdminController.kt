package dev.gpucodeforces.backend.admin

import dev.gpucodeforces.backend.api.AdminApi
import dev.gpucodeforces.backend.api.model.RunAdminScript200Response
import dev.gpucodeforces.backend.api.model.RunAdminScriptRequest
import dev.gpucodeforces.backend.api.model.SyncProblems200Response
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.problem.ProblemSyncService
import dev.gpucodeforces.backend.user.User
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.view.RedirectView

// SecurityConfig requires a session on /api/admin/**; the admin check is here.
@RestController
class AdminController(
    private val adminProperties: AdminProperties,
    private val console: GroovyConsole,
    private val problemSyncService: ProblemSyncService,
) : AdminApi {
    override fun runAdminScript(runAdminScriptRequest: RunAdminScriptRequest): ResponseEntity<RunAdminScript200Response> {
        val admin = requireAdmin()
        val result = console.run(runAdminScriptRequest.script, admin.handle)
        return ResponseEntity.ok(RunAdminScript200Response(result.output, result.result, result.error))
    }

    override fun syncProblems(): ResponseEntity<SyncProblems200Response> {
        requireAdmin()
        val result = problemSyncService.sync()
        return ResponseEntity.ok(SyncProblems200Response(result.revision, result.imported, result.unchanged, result.removed, result.failed))
    }

    private fun requireAdmin(): User {
        val user = CurrentUser.resolve()!!
        if (!adminProperties.isAdmin(user.handle)) throw ForbiddenException("Нужны права администратора")
        return user
    }
}

/** The console page itself is static (resources/static/admin); it calls the API above with the user's session. */
@RestController
class AdminPageController {
    @GetMapping("/admin", "/admin/")
    fun page() = RedirectView("/admin/index.html")
}
