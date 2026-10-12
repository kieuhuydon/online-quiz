package quiz.server.service;

import quiz.common.dto.CreateAccountDto;
import quiz.common.enums.Role;
import quiz.server.db.TransactionManager;
import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.ForbiddenException;
import quiz.server.exception.NotFoundException;
import quiz.server.exception.UnauthorizedException;
import quiz.server.exception.ValidationException;
import quiz.server.repository.UserRepository;
import quiz.server.security.PasswordHasher;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/** Admin account operations, with actor checks independent of the network guard. */
public final class AccountService {
    private final UserRepository users;
    private final TransactionManager transactions;
    private final SessionCoordinator sessions;

    public AccountService(UserRepository users, TransactionManager transactions, SessionCoordinator sessions) {
        this.users = Objects.requireNonNull(users, "UserRepository cannot be null");
        this.transactions = Objects.requireNonNull(transactions, "TransactionManager cannot be null");
        this.sessions = Objects.requireNonNull(sessions, "SessionCoordinator cannot be null");
    }

    /** Creates only Teacher/Student accounts; creator identity comes from the authenticated actor. */
    public User createAccount(CreateAccountDto dto, long adminId) {
        requireAdmin(adminId);
        CreateAccountDto validated = validateAccount(dto);
        if (users.existsByUsername(validated.username())) {
            throw new ConflictException("Username is already in use");
        }
        if (validated.email() != null && users.existsByEmail(validated.email())) {
            throw new ConflictException("Email is already in use");
        }
        String hash = PasswordHasher.hashPassword(validated.password());
        User input = User.createNew(validated.username(), hash, validated.fullName(),
            validated.email(), validated.role(), adminId);
        return transactions.executeInTransaction(connection -> {
            requireAdmin(connection, adminId);
            return users.save(connection, input);
        });
    }

    /** Returns domain objects for subsequent DTO mapping; hashes must never be sent to clients. */
    public List<User> listAccounts(long adminId) {
        requireAdmin(adminId);
        return List.copyOf(users.findAll());
    }

    /** Commits the status change before revoking sessions; reopening never restores old tokens. */
    public void setAccountActive(long userId, boolean active, long adminId) {
        ServiceValidation.positiveId(userId);
        requireAdmin(adminId);
        sessions.withUserLock(userId, () -> {
            transactions.executeInTransaction(connection -> {
                requireAdmin(connection, adminId);
                User target = users.findById(connection, userId)
                    .orElseThrow(() -> new NotFoundException("Account was not found"));
                if (target.role() == Role.ADMIN) {
                    throw new ForbiddenException("Admin account status cannot be changed through this API");
                }
                if (!users.updateActiveStatus(connection, userId, active)) {
                    throw new NotFoundException("Account was not found");
                }
                return null;
            });
            if (!active) {
                sessions.revokeUser(userId);
            }
            return null;
        });
    }

    private CreateAccountDto validateAccount(CreateAccountDto dto) {
        if (dto == null) {
            throw new ValidationException("Account data is required");
        }
        String username = ServiceValidation.requiredText(dto.username(), "Username", 50);
        String name = ServiceValidation.requiredText(dto.fullName(), "Full name", 100);
        String email = ServiceValidation.email(dto.email());
        ServiceValidation.password(dto.password(), true);
        if (dto.role() == null) {
            throw new ValidationException("Role is required");
        }
        if (dto.role() == Role.ADMIN) {
            throw new ForbiddenException("Only Teacher and Student accounts can be created");
        }
        return new CreateAccountDto(username, dto.password(), name, email, dto.role());
    }

    private void requireAdmin(long adminId) {
        ServiceValidation.positiveId(adminId);
        checkAdmin(users.findById(adminId).orElse(null));
    }

    private void requireAdmin(Connection connection, long adminId) {
        checkAdmin(users.findById(connection, adminId).orElse(null));
    }

    private void checkAdmin(User actor) {
        if (actor == null || !actor.active()) {
            throw new UnauthorizedException("Acting account is not active");
        }
        if (actor.role() != Role.ADMIN) {
            throw new ForbiddenException("Admin role is required");
        }
    }
}
