package quiz.server.repository;

import quiz.server.domain.User;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for users. Authorization belongs to application services.
 * Usernames and emails are already normalized by the caller; lookups preserve
 * case and follow the database's existing equality and uniqueness rules.
 * Results are non-null, and returned users have positive IDs and creation timestamps.
 *
 * <p>Ordinary methods acquire and release their own connection. Connection overloads
 * use only the supplied, open connection, which remains owned by the caller. They
 * must not acquire another connection, close the supplied connection, change its
 * auto-commit mode, commit or roll back. This lets services coordinate several
 * operations through TransactionManager.</p>
 *
 * <p>Invalid arguments produce {@link IllegalArgumentException}. Database failures
 * produce {@link QuizException} with DATABASE_ERROR, a safe message and the original
 * cause; they must not be disguised as an absent user, empty list or false result.
 * Username/email unique violations on insertion produce {@link ConflictException}.
 * Existence checks are advisory; the database's UNIQUE constraints resolve races.</p>
 */
public interface UserRepository {

    /**
     * Finds a user by exact, normalized username, including inactive accounts.
     *
     * @param username nonblank username of at most 50 Unicode code points
     * @return the persisted user, or empty if the username does not exist
     * @throws IllegalArgumentException if the username is null, blank or too long
     * @throws QuizException if the database operation fails
     */
    Optional<User> findByUsername(String username);

    /**
     * Finds a user by ID, including inactive accounts.
     *
     * @param id positive user ID
     * @return the persisted user, or empty if the ID does not exist
     * @throws IllegalArgumentException if the ID is not positive
     * @throws QuizException if the database operation fails
     */
    Optional<User> findById(long id);

    /**
     * Finds a user using the caller's connection without managing its lifecycle
     * or transaction. Suitable for checking actors and targets in a write transaction.
     *
     * @param connection non-null, open connection owned by the caller
     * @param id positive user ID
     * @return the persisted user, or empty if the ID does not exist
     * @throws IllegalArgumentException if the connection is null or the ID is not positive
     * @throws QuizException if the database operation fails
     * @see #findById(long)
     */
    Optional<User> findById(Connection connection, long id);

    /**
     * Checks whether an exact, normalized username is already used, regardless
     * of account status. This does not reserve the username for a later insertion.
     *
     * @param username nonblank username of at most 50 Unicode code points
     * @return true if a user with that username exists
     * @throws IllegalArgumentException if the username is null, blank or too long
     * @throws QuizException if the database operation fails
     */
    boolean existsByUsername(String username);

    /**
     * Checks whether an exact, normalized email is already used, regardless of
     * account status. A null email is optional and returns false without a query;
     * blank optional emails must be normalized to null by the caller.
     *
     * @param email null or a nonblank email of at most 100 Unicode code points
     * @return true if a user with that non-null email exists; false for null
     * @throws IllegalArgumentException if a non-null email is blank or too long
     * @throws QuizException if the database operation fails
     */
    boolean existsByEmail(String email);

    /**
     * Inserts a new user. Does not update or upsert a previously persisted user.
     * The database generates the ID and creation timestamp; the input is immutable.
     *
     * @param user non-null new user with ID zero and no creation timestamp
     * @return a new persisted User containing the database-generated ID and timestamp
     * @throws IllegalArgumentException if the user is null or already persisted
     * @throws ConflictException if the username or non-null email is already used
     * @throws QuizException if another database operation fails
     */
    User save(User user);

    /**
     * Inserts a new user using only the caller's connection. The returned user
     * has database metadata, but persistence is not final until the caller commits.
     *
     * @param connection non-null, open connection owned by the caller
     * @param user non-null new user with ID zero and no creation timestamp
     * @return a new User containing the database-generated ID and timestamp
     * @throws IllegalArgumentException if the connection/user is null or the user is persisted
     * @throws ConflictException if the username or non-null email is already used
     * @throws QuizException if another database operation fails
     * @see #save(User)
     */
    User save(Connection connection, User user);

    /**
     * Lists all persisted users, including inactive accounts, ordered by ID ascending.
     *
     * @return a non-null list with no null elements; empty only when no users exist
     * @throws QuizException if the database operation fails
     */
    List<User> findAll();

    /**
     * Sets the account status without deleting the user or changing other fields.
     * Returns true for an existing user even when its status was already the same.
     * Token invalidation after a successful commit belongs to the service layer.
     *
     * @param id positive user ID
     * @param active desired status
     * @return true if the ID exists; false if no user has that ID
     * @throws IllegalArgumentException if the ID is not positive
     * @throws QuizException if the database operation fails
     */
    boolean updateActiveStatus(long id, boolean active);

    /**
     * Sets the status using only the caller's connection. Changes are not final
     * until the caller commits; this method does not manage sessions or connections.
     *
     * @param connection non-null, open connection owned by the caller
     * @param id positive user ID
     * @param active desired status
     * @return true if the ID exists, even if the status is unchanged; false otherwise
     * @throws IllegalArgumentException if the connection is null or the ID is not positive
     * @throws QuizException if the database operation fails
     * @see #updateActiveStatus(long, boolean)
     */
    boolean updateActiveStatus(Connection connection, long id, boolean active);
}
