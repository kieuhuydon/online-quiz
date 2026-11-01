package quiz.client.ui.teacher;

import javafx.css.PseudoClass;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import quiz.common.enums.MonitoringStatus;
import java.util.Objects;
import java.util.function.Function;

/** Shared styling for monitoring, violation logs and anti-cheat results. */
final class ViolationTableStyle {
    private static final PseudoClass WARNING=PseudoClass.getPseudoClass("warning");
    private static final PseudoClass CHEATING=PseudoClass.getPseudoClass("cheating");
    private ViolationTableStyle() { }

    static <T> void install(TableView<T> table,Function<T,MonitoringStatus> status) {
        table.getStyleClass().add("violation-table");
        table.getStylesheets().add(Objects.requireNonNull(ViolationTableStyle.class
            .getResource("/quiz/client/ui/monitoring.css")).toExternalForm());
        table.setRowFactory(view->new TableRow<>() {
            @Override protected void updateItem(T item,boolean empty) {
                super.updateItem(item,empty);
                MonitoringStatus value=empty||item==null?null:status.apply(item);
                // Always clear both states: JavaFX reuses rows while scrolling and refreshing.
                pseudoClassStateChanged(WARNING,value==MonitoringStatus.WARNING);
                pseudoClassStateChanged(CHEATING,value==MonitoringStatus.CHEATING);
            }
        });
    }
}
