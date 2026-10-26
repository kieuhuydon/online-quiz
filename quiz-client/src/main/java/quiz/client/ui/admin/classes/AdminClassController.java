package quiz.client.ui.admin.classes;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.StringConverter;
import quiz.client.api.ApiException;
import quiz.client.api.QuizApi;
import quiz.client.ui.ScreenController;
import quiz.client.util.FxAsync;
import quiz.common.dto.*;
import quiz.common.enums.Role;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Admin owns class creation, teacher assignment at creation and membership writes. */
public final class AdminClassController extends ScreenController {
    private final QuizApi api;
    private final FxAsync fx;
    @FXML private TableView<ClassDto> classes;
    @FXML private TableView<ClassMemberDto> members;
    @FXML private ComboBox<AccountDto> teacher;
    @FXML private TableView<AccountDto> students;
    @FXML private TextField className, description;
    @FXML private Label notice, memberTitle, selectedCount;
    @FXML private Button createClass, addMember, removeMember, refresh;
    @FXML private Button selectAllStudents, clearStudents;
    private boolean busy;
    private boolean membersFresh;
    private long memberRevision;
    private List<AccountDto> studentAccounts = List.of();
    private final Map<Long, BooleanProperty> picked = new LinkedHashMap<>();
    private final Map<Long, String> enrollmentNotes = new LinkedHashMap<>();

    public AdminClassController(QuizApi api, FxAsync fx) { this.api = api; this.fx = fx; }

    @FXML private void initialize() {
        column(classes, "Tên lớp", ClassDto::name);
        column(classes, "Giáo viên phụ trách", ClassDto::teacherName);
        column(classes, "Mô tả", row -> row.description() == null ? "" : row.description());
        column(members, "Tên đăng nhập", ClassMemberDto::studentUsername);
        column(members, "Họ và tên", ClassMemberDto::studentFullName);
        classes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        members.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        classes.setPlaceholder(new Label("Chưa có lớp học."));
        members.setPlaceholder(new Label("Chọn lớp để xem học viên."));
        StringConverter<AccountDto> names = new StringConverter<>() {
            @Override public String toString(AccountDto row) { return row == null ? "" : row.fullName() + " (@" + row.username() + ")"; }
            @Override public AccountDto fromString(String text) { throw new UnsupportedOperationException(); }
        };
        teacher.setConverter(names);
        TableColumn<AccountDto, Boolean> check = new TableColumn<>("Chọn");
        check.setCellValueFactory(row -> selection(row.getValue().id()));
        check.setCellFactory(CheckBoxTableCell.forTableColumn(check));
        check.setMaxWidth(65); check.setMinWidth(55); check.setSortable(false);
        students.getColumns().add(check);
        column(students, "Họ và tên", AccountDto::fullName);
        column(students, "Tên đăng nhập", AccountDto::username);
        column(students, "Trạng thái", row -> enrollmentNotes.getOrDefault(row.id(), ""));
        students.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        students.setPlaceholder(new Label("Chọn lớp để tải học viên có thể thêm."));
        classes.getSelectionModel().selectedItemProperty().addListener((o, before, selected) -> loadMembers());
        members.getSelectionModel().selectedItemProperty().addListener((o, before, selected) -> controls());
        controls();
    }

    private static <T> void column(TableView<T> table, String title, Function<T, String> text) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(text.apply(row.getValue())));
        table.getColumns().add(column);
    }

    @Override protected void onMount() { reload(); }

    @FXML private void reload() {
        if (!isActive() || busy) return;
        setBusy(true);
        fx.observe(api.listAccounts().thenCombine(api.listClasses(), Data::new), this::isActive, data -> {
            var selected = classes.getSelectionModel().getSelectedItem();
            teacher.getItems().setAll(data.accounts().stream().filter(a -> a.role() == Role.TEACHER && a.active()).toList());
            studentAccounts = data.accounts().stream().filter(a -> a.role() == Role.STUDENT && a.active()).toList();
            classes.getItems().setAll(data.classes());
            if (selected != null) data.classes().stream().filter(c -> c.id() == selected.id()).findFirst()
                .ifPresent(c -> classes.getSelectionModel().select(c));
        }, this::failed, () -> setBusy(false));
    }

    private void loadMembers() {
        picked.clear();
        enrollmentNotes.clear();
        refreshMembers();
    }

    private void refreshMembers() {
        long version = ++memberRevision;
        membersFresh = false;
        members.getItems().clear();
        students.getItems().clear();
        ClassDto selected = classes.getSelectionModel().getSelectedItem();
        memberTitle.setText(selected == null ? "Học viên trong lớp" : "Học viên lớp " + selected.name());
        controls();
        if (selected == null) return;
        fx.observe(api.listClassMembers(selected.id()), () -> isActive() && version == memberRevision,
            rows -> {
                members.getItems().setAll(rows);
                Set<Long> enrolled = rows.stream().map(ClassMemberDto::studentId).collect(Collectors.toSet());
                picked.keySet().removeAll(enrolled);
                students.getItems().setAll(studentAccounts.stream().filter(a -> !enrolled.contains(a.id())).toList());
                students.setPlaceholder(new Label("Không còn học viên đang hoạt động để thêm vào lớp."));
                membersFresh = true;
            }, failure -> message((notice.isVisible() ? notice.getText() + " " : "")
                + "Chưa tải được danh sách học viên. Hãy tải lại trước khi thêm tiếp.", true), this::controls);
    }

    @FXML private void create() {
        if (!isActive() || busy) return;
        String name = className.getText().strip();
        String desc = description.getText().strip();
        AccountDto selected = teacher.getValue();
        if (name.isBlank() || name.length() > 100 || desc.length() > 255 || selected == null) {
            message("Nhập tên lớp (tối đa 100 ký tự), mô tả (tối đa 255 ký tự) và chọn giáo viên.", true); return;
        }
        write(api.createClass(new CreateClassDto(name, desc, selected.id())), created -> {
            message("Đã tạo lớp “" + created.name() + "”.", false);
            className.clear(); description.clear();
            classes.getItems().add(created);
            classes.getSelectionModel().select(created);
        });
    }

    @FXML private void add() {
        if (!isActive() || busy || !membersFresh) return;
        var selected = classes.getSelectionModel().getSelectedItem();
        List<Long> ids = students.getItems().stream().filter(a -> selection(a.id()).get()).map(AccountDto::id).toList();
        if (selected == null || ids.isEmpty()) { message("Chọn lớp và học viên cần thêm.", true); return; }
        enrollmentNotes.clear();
        write(enroll(selected.id(), ids), result -> {
            result.added.forEach(picked::remove);
            result.existing.forEach(picked::remove);
            enrollmentNotes.putAll(result.notes);
            int pending = ids.size() - result.added.size() - result.existing.size() - result.rejected;
            String summary = "Đã thêm " + result.added.size() + "/" + ids.size() + " học viên.";
            if (!result.existing.isEmpty()) summary += " " + result.existing.size() + " học viên đã có trong lớp.";
            if (result.rejected > 0) summary += " " + result.rejected + " học viên không thêm được; xem trạng thái trong bảng.";
            if (pending > 0) summary += " " + pending + " học viên chưa xác nhận hoặc chưa xử lý. Tải lại trước khi thử lại.";
            message(summary, result.rejected > 0 || pending > 0);
            refreshMembers();
        });
    }

    /** Sequential requests preserve individual results; stop on a lost/invalid connection. */
    private CompletableFuture<Enrollment> enroll(long classId, List<Long> ids) {
        CompletableFuture<Enrollment> chain = CompletableFuture.completedFuture(new Enrollment());
        for (long id : ids) chain = chain.thenCompose(result -> {
            if (result.stopped) {
                result.notes.put(id, "Chưa xử lý");
                return CompletableFuture.completedFuture(result);
            }
            return api.addClassMember(classId, id).handle((ignored, failure) -> {
                Throwable cause = failure == null ? null : FxAsync.unwrap(failure);
                if (cause == null) result.added.add(id);
                else if (cause instanceof ApiException error) {
                    switch (error.errorCode()) {
                        case CONFLICT -> result.existing.add(id);
                        case VALIDATION_ERROR, NOT_FOUND -> {
                            result.rejected++;
                            result.notes.put(id, error.errorCode() == quiz.common.protocol.ErrorCode.NOT_FOUND
                                ? "Tài khoản không còn tồn tại" : "Tài khoản không hợp lệ hoặc đã khóa");
                        }
                        default -> {
                            result.stopped = true;
                            result.notes.put(id, "Không hoàn tất yêu cầu; hãy tải lại");
                        }
                    }
                } else {
                    result.stopped = true;
                    result.notes.put(id, "Chưa xác nhận kết quả; hãy tải lại");
                }
                return result;
            });
        });
        return chain;
    }

    private BooleanProperty selection(long id) {
        return picked.computeIfAbsent(id, ignored -> {
            BooleanProperty value = new SimpleBooleanProperty();
            value.addListener((o, before, after) -> controls());
            return value;
        });
    }

    @FXML private void selectAll() {
        if (!busy && membersFresh) students.getItems().forEach(a -> selection(a.id()).set(true));
    }

    @FXML private void clearSelection() {
        if (!busy) picked.values().forEach(value -> value.set(false));
    }

    private static final class Enrollment {
        final List<Long> added = new ArrayList<>();
        final List<Long> existing = new ArrayList<>();
        final Map<Long, String> notes = new LinkedHashMap<>();
        int rejected;
        boolean stopped;
    }

    @FXML private void remove() {
        if (!isActive() || busy) return;
        var selected = classes.getSelectionModel().getSelectedItem();
        var member = members.getSelectionModel().getSelectedItem();
        if (selected == null || member == null) return;
        write(api.removeClassMember(selected.id(), member.studentId()), ignored -> {
            message("Đã xóa học viên khỏi lớp.", false); refreshMembers();
        });
    }

    private <T> void write(CompletableFuture<T> operation, java.util.function.Consumer<T> success) {
        setBusy(true);
        fx.observe(operation, this::isActive, success, this::failed, () -> setBusy(false));
    }

    private void failed(Throwable failure) {
        String text = "Chưa xác nhận được kết quả. Tải lại dữ liệu trước khi thử lại.";
        if (FxAsync.unwrap(failure) instanceof ApiException error) text = switch (error.errorCode()) {
            case CONFLICT -> "Tên lớp đã tồn tại hoặc học viên đã ở trong lớp.";
            case VALIDATION_ERROR -> "Thông tin không hợp lệ; kiểm tra giáo viên và học viên đang hoạt động.";
            case FORBIDDEN -> "Chỉ Admin được thay đổi lớp và học viên.";
            case NOT_FOUND -> "Lớp hoặc tài khoản không còn tồn tại. Hãy tải lại.";
            default -> "Không thể xử lý yêu cầu. Hãy tải lại và thử lại.";
        };
        message(text, true);
    }

    private void message(String text, boolean error) {
        notice.setText(text); notice.setVisible(true); notice.setManaged(true);
        notice.getStyleClass().remove("error-notice");
        if (error) notice.getStyleClass().add("error-notice");
    }

    private void setBusy(boolean value) { busy = value; controls(); }
    private void controls() {
        createClass.setDisable(busy); refresh.setDisable(busy); classes.setDisable(busy);
        teacher.setDisable(busy); students.setDisable(busy || !membersFresh); className.setDisable(busy); description.setDisable(busy);
        long count = students.getItems().stream().filter(a -> picked.containsKey(a.id()) && picked.get(a.id()).get()).count();
        selectedCount.setText("Đã chọn " + count + " học viên");
        selectAllStudents.setDisable(busy || !membersFresh || students.getItems().isEmpty());
        clearStudents.setDisable(busy || count == 0);
        addMember.setDisable(busy || !membersFresh || count == 0);
        removeMember.setDisable(busy || !membersFresh || members.getSelectionModel().getSelectedItem() == null);
    }
    private record Data(List<AccountDto> accounts, List<ClassDto> classes) { }
}
