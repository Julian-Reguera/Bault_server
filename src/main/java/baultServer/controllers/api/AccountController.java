package baultServer.controllers.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import baultServer.model.User;
import baultServer.repositorys.UserRepository;
import baultServer.services.AccountService;
import baultServer.services.AccountService.AccountDto;
import baultServer.services.AccountService.PlanDto;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
public class AccountController {

    private final UserRepository userRepository;
    private final AccountService accountService;

    public AccountController(UserRepository userRepository, AccountService accountService) {
        this.userRepository = userRepository;
        this.accountService = accountService;
    }

    /** Toda la info que necesita la pantalla Cuenta: user + plan actual + usage + fechas. */
    @GetMapping(path = "/api/account", produces = "application/json")
    public AccountDto account(@AuthenticationPrincipal UserDetails principal) {
        return accountService.getAccount(currentUser(principal));
    }

    /** Catalogo de planes disponibles (enabled=true). Marca isCurrent en el del user. */
    @GetMapping(path = "/api/plans", produces = "application/json")
    public List<PlanDto> plans(@AuthenticationPrincipal UserDetails principal) {
        return accountService.listPlans(currentUser(principal));
    }

    private User currentUser(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
    }
}
