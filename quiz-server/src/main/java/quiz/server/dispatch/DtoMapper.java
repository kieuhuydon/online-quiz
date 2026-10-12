package quiz.server.dispatch;

import quiz.common.dto.AccountDto;
import quiz.common.dto.LoginResultDto;
import quiz.server.domain.User;
import quiz.server.service.AuthResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Auth/Account wire mapping; password hashes are deliberately excluded. */
public final class DtoMapper {
    private DtoMapper() {
    }

    public static AccountDto account(User user) {
        Objects.requireNonNull(user, "User cannot be null");
        return new AccountDto(user.id(), user.username(), user.fullName(), user.email(), user.role(),
            user.active(), user.createdBy(), user.createdAt());
    }

    public static LoginResultDto login(AuthResult result) {
        Objects.requireNonNull(result, "AuthResult cannot be null");
        User user = result.user();
        return new LoginResultDto(result.token(), user.id(), user.username(), user.fullName(), user.role());
    }

    public static ArrayList<AccountDto> accounts(List<User> users) {
        Objects.requireNonNull(users, "Users cannot be null");
        ArrayList<AccountDto> accounts = new ArrayList<>(users.size());
        for (User user : users) {
            accounts.add(account(user));
        }
        return accounts;
    }
}
