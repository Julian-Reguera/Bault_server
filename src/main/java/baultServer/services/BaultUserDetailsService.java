package baultServer.services;

import java.util.ArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import baultServer.repositorys.UserRepository;
import baultServer.model.User;

@Service
public class BaultUserDetailsService implements UserDetailsService {
    private static Logger log = LogManager.getLogger(UserDetailsService.class);
    private final UserRepository userRepository;

    public BaultUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + username));

        ArrayList<SimpleGrantedAuthority> roles = new ArrayList<>();
        for (String r : u.getRoles().split("[,]")) {
            roles.add(new SimpleGrantedAuthority("ROLE_" + r));
            log.info("Roles for " + username + " include " + roles.get(roles.size()-1));
        }

        return new org.springframework.security.core.userdetails.User(
                u.getEmail(),
                u.getPasswordHash(),
                u.isEnabled(),
                true,   // accountNonExpired
                true,   // credentialsNonExpired
                true,   // accountNonLocked
                roles);
    }

}
