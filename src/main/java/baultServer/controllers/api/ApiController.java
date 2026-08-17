package baultServer.controllers.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import baultServer.exceptions.ApiErrorCode;
import baultServer.exceptions.ApiException;
import baultServer.model.User;
import baultServer.repositorys.UserRepository;
import baultServer.services.AccountService;
import baultServer.services.AccountService.AccountDto;
import baultServer.services.PlanService;
import baultServer.services.PlanService.PlanDto;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final UserRepository userRepository;
    private final AccountService accountService;
    private final PlanService planService;

    public ApiController(UserRepository userRepository, AccountService accountService, PlanService planService) {
        this.userRepository = userRepository;
        this.accountService = accountService;
        this.planService = planService;
    }

    /** Toda la info que necesita la pantalla Cuenta: user + plan actual + usage + fechas. */
    @GetMapping(path = "/account", produces = "application/json")
    public AccountDto account(@AuthenticationPrincipal UserDetails principal) {
        return accountService.getAccount(currentUser(principal));
    }

    /** Catalogo de planes disponibles (enabled=true). Marca isCurrent en el del user. */
    @GetMapping(path = "/plans", produces = "application/json")
    public List<PlanDto> plans(@AuthenticationPrincipal UserDetails principal) {
        return planService.listPlans(currentUser(principal));
    }

    private User currentUser(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ApiException(ApiErrorCode.USER_NOT_FOUND));
    }
}
