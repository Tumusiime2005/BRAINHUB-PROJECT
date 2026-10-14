package com.brainhub;

import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public final class BrainHubApp extends Application {
    private static final String NAVY = "#14213D";
    private static final String BLUE = "#2563EB";
    private static final String INK = "#172033";
    private static final String MUTED = "#64748B";
    private static final String SURFACE = "#FFFFFF";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault());

    private final AuthService authService = new AuthService();
    private final DocumentService documentService = new DocumentService();
    private final BorderPane root = new BorderPane();
    private Stage stage;
    private User activeUser;
    private String startupDatabaseWarning = "";

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("BRAIN HUB | Research Publishing");
        stage.setMinWidth(980);
        stage.setMinHeight(680);
        if (!Database.isAvailable()) {
            startupDatabaseWarning = "WampServer's database is not reachable. Start MySQL in WampServer "
                    + "and import src/main/resources/database/brainhub.sql.";
        }
        Scene scene = new Scene(root, 1180, 780);
        scene.getStylesheets().clear();
        root.setStyle("-fx-background-color: #F3F6FB; -fx-font-family: 'Segoe UI', Arial;");
        stage.setScene(scene);
        showSignIn("");
        stage.show();
    }

    private void showSignIn(String message) {
        Label brand = new Label("BRAIN HUB");
        brand.setStyle("-fx-font-size: 34px; -fx-font-weight: 800; -fx-text-fill: " + NAVY + ";");
        Label tagline = new Label("Research, reviewed and published.");
        tagline.setStyle("-fx-font-size: 15px; -fx-text-fill: " + MUTED + ";");

        TextField email = new TextField();
        email.setPromptText("Email address");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        styleField(email);
        styleField(password);

        Label feedback = new Label(message.isBlank() ? startupDatabaseWarning : message);
        feedback.setWrapText(true);
        feedback.setStyle("-fx-text-fill: " + (message.isBlank() ? "#B45309" : "#B91C1C") + ";");

        Button signIn = primaryButton("Sign in");
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.setOnAction(event -> {
            try {
                activeUser = authService.signIn(email.getText(), password.getText());
                startupDatabaseWarning = "";
                showDashboard();
            } catch (Exception exception) {
                feedback.setText(userMessage(exception));
            }
        });

        Button researcherSignUp = linkButton("Create a researcher account");
        researcherSignUp.setOnAction(event -> showRegistration(User.Role.RESEARCHER));
        Button adminSignUp = linkButton("Set up an admin account");
        adminSignUp.setOnAction(event -> showRegistration(User.Role.ADMIN));

        VBox card = new VBox(15,
                heading("Welcome back"),
                text("Sign in to manage research submissions and publications."),
                fieldLabel("EMAIL"), email,
                fieldLabel("PASSWORD"), password,
                feedback, signIn,
                new HBox(8, researcherSignUp, adminSignUp));
        card.setPadding(new Insets(34));
        card.setMaxWidth(470);
        card.setStyle(cardStyle());
        VBox page = new VBox(14, brand, tagline, card);
        page.setAlignment(Pos.CENTER);
        page.setPadding(new Insets(38));
        root.setCenter(page);
        root.setTop(null);
        root.setLeft(null);
        root.setRight(null);
        root.setBottom(null);
    }

    private void showRegistration(User.Role role) {
        Label brand = new Label("BRAIN HUB");
        brand.setStyle("-fx-font-size: 30px; -fx-font-weight: 800; -fx-text-fill: " + NAVY + ";");
        Label title = heading(role == User.Role.ADMIN ? "Set up an admin account" : "Create your researcher account");
        Label details = text(role == User.Role.ADMIN
                ? "Admin setup requires the private setup code configured for this computer."
                : "Your account lets you submit work, track reviews and access published documents.");
        TextField name = new TextField();
        name.setPromptText("Full name");
        TextField email = new TextField();
        email.setPromptText("Email address");
        PasswordField password = new PasswordField();
        password.setPromptText("Password (at least 8 characters)");
        TextField setupCode = new TextField();
        setupCode.setPromptText("Admin setup code");
        styleField(name);
        styleField(email);
        styleField(password);
        styleField(setupCode);
        Label feedback = new Label();
        feedback.setWrapText(true);
        feedback.setStyle("-fx-text-fill: #B91C1C;");

        VBox form = new VBox(12, title, details, fieldLabel("FULL NAME"), name,
                fieldLabel("EMAIL"), email, fieldLabel("PASSWORD"), password);
        if (role == User.Role.ADMIN) {
            form.getChildren().addAll(fieldLabel("ADMIN SETUP CODE"), setupCode);
        }
        Button create = primaryButton("Create account");
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(event -> {
            try {
                if (role == User.Role.ADMIN) {
                    activeUser = authService.registerAdmin(name.getText(), email.getText(), password.getText(),
                            setupCode.getText());
                } else {
                    activeUser = authService.registerResearcher(name.getText(), email.getText(), password.getText());
                }
                startupDatabaseWarning = "";
                showDashboard();
            } catch (Exception exception) {
                feedback.setText(userMessage(exception));
            }
        });
        Button back = linkButton("Back to sign in");
        back.setOnAction(event -> showSignIn(""));
        VBox card = new VBox(14, form, feedback, create, back);
        card.setPadding(new Insets(32));
        card.setMaxWidth(500);
        card.setStyle(cardStyle());
        VBox page = new VBox(18, brand, card);
        page.setAlignment(Pos.CENTER);
        page.setPadding(new Insets(36));
        root.setCenter(page);
        root.setTop(null);
    }

    private void showDashboard() {
        showShell();
        VBox content = new VBox(22);
        content.setPadding(new Insets(34));
        Label greeting = heading("Good day, " + activeUser.fullName());
        Label subtitle = text(activeUser.role() == User.Role.ADMIN
                ? "Monitor submissions and manage the review queue."
                : "Your research workspace at a glance.");
        content.getChildren().addAll(greeting, subtitle);
        try {
            DocumentService.DashboardCounts counts = documentService.getDashboardCounts(activeUser);
            HBox cards = new HBox(14,
                    metricCard("SUBMITTED", counts.submitted(), "Awaiting review"),
                    metricCard("UNDER REVIEW", counts.underReview(), "Being assessed"),
                    metricCard("REJECTED", counts.rejected(), "Requires changes"),
                    metricCard("TOTAL DOCUMENTS", counts.total(), "All records"));
            for (Node node : cards.getChildren()) {
                HBox.setHgrow(node, Priority.ALWAYS);
            }
            content.getChildren().add(cards);
            Label next = heading(activeUser.role() == User.Role.ADMIN ? "Review workflow" : "What you can do");
            Label guide = text(activeUser.role() == User.Role.ADMIN
                    ? "Open Documents to review assigned submissions, approve or reject work, and publish approved research."
                    : "Submit a document, follow its review status, and download published work after signing in.");
            guide.setWrapText(true);
            content.getChildren().addAll(next, guide);
        } catch (Exception exception) {
            showInlineError(content, userMessage(exception));
        }
        root.setCenter(content);
    }

    private void showShell() {
        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 26, 16, 26));
        header.setStyle("-fx-background-color: " + SURFACE + "; -fx-border-color: #E2E8F0; -fx-border-width: 0 0 1 0;");
        Label brand = new Label("BRAIN HUB");
        brand.setStyle("-fx-font-size: 19px; -fx-font-weight: 800; -fx-text-fill: " + NAVY + ";");
        Label account = new Label(activeUser.fullName() + "  ·  " + activeUser.role());
        account.setStyle("-fx-text-fill: " + MUTED + ";");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button logout = new Button("Log out");
        logout.setStyle("-fx-background-color: #EEF2FF; -fx-text-fill: " + NAVY + "; -fx-font-weight: 700; -fx-padding: 9 16 9 16;");
        logout.setOnAction(event -> {
            activeUser = null;
            showSignIn("");
        });
        header.getChildren().addAll(brand, spacer, account, logout);

        VBox navigation = new VBox(8);
        navigation.setPadding(new Insets(24, 14, 24, 14));
        navigation.setPrefWidth(215);
        navigation.setStyle("-fx-background-color: " + SURFACE + "; -fx-border-color: #E2E8F0; -fx-border-width: 0 1 0 0;");
        navigation.getChildren().addAll(
                navButton("Dashboard", this::showDashboard),
                navButton("Documents", this::showDocuments),
                navButton("Notifications", this::showNotifications));
        if (activeUser.role() == User.Role.RESEARCHER) {
            navigation.getChildren().add(navButton("Submit a document", this::showSubmitDocument));
        }
        root.setTop(header);
        root.setLeft(navigation);
        root.setRight(null);
        root.setBottom(null);
    }

    private void showDocuments() {
        showShell();
        VBox page = new VBox(16);
        page.setPadding(new Insets(28));
        Label title = heading(activeUser.role() == User.Role.ADMIN ? "Assigned research" : "My documents and publications");
        Label caption = text(activeUser.role() == User.Role.ADMIN
                ? "Review each assigned submission. Approving a document and publishing it are separate steps."
                : "Your submissions and all published documents available to signed-in users.");
        caption.setWrapText(true);

        TableView<DocumentService.DocumentRecord> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(330);
        table.getColumns().add(stringColumn("TITLE", DocumentService.DocumentRecord::title));
        if (activeUser.role() == User.Role.ADMIN) {
            table.getColumns().add(stringColumn("RESEARCHER", DocumentService.DocumentRecord::researcherName));
        }
        table.getColumns().add(stringColumn("STATUS", DocumentService.DocumentRecord::status));
        table.getColumns().add(stringColumn("FILE", DocumentService.DocumentRecord::originalFileName));
        table.getColumns().add(stringColumn("SUBMITTED", doc -> doc.submittedAt() == null
            ? "—" : DATE_FORMAT.format(doc.submittedAt().toInstant())));

        Label details = new Label("Select a document to see its details and available actions.");
        details.setWrapText(true);
        details.setStyle("-fx-text-fill: " + MUTED + "; -fx-padding: 12 0 0 0;");
        TextArea abstractView = new TextArea();
        abstractView.setEditable(false);
        abstractView.setWrapText(true);
        abstractView.setPromptText("Document abstract");
        abstractView.setPrefRowCount(3);
        HBox actions = new HBox(9);
        actions.setAlignment(Pos.CENTER_LEFT);

        table.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            actions.getChildren().clear();
            if (selected == null) {
                details.setText("Select a document to see its details and available actions.");
                abstractView.clear();
                return;
            }
            details.setText(selected.title() + "  ·  " + selected.researcherName()
                    + "  ·  " + selected.researcherEmail() + "  ·  " + selected.status());
            abstractView.setText(selected.abstractText() == null || selected.abstractText().isBlank()
                    ? "No abstract provided." : selected.abstractText());
            if (activeUser.role() == User.Role.ADMIN) {
                if (selected.status().equals("SUBMITTED")) {
                    actions.getChildren().add(actionButton("Start review", () -> {
                        try {
                            documentService.markUnderReview(activeUser, selected.id());
                            showDocuments();
                        } catch (Exception exception) {
                            showError(exception);
                        }
                    }));
                }
                if (selected.status().equals("SUBMITTED") || selected.status().equals("UNDER_REVIEW")) {
                    actions.getChildren().add(actionButton("Approve", () -> decide(selected, true)));
                    actions.getChildren().add(actionButton("Reject", () -> decide(selected, false)));
                }
                if (selected.status().equals("APPROVED")) {
                    actions.getChildren().add(actionButton("Publish", () -> {
                        try {
                            documentService.publish(activeUser, selected.id());
                            showDocuments();
                        } catch (Exception exception) {
                            showError(exception);
                        }
                    }));
                }
            }
            if (activeUser.role() == User.Role.ADMIN
                    || selected.researcherId() == activeUser.id()
                    || selected.status().equals("PUBLISHED")) {
                actions.getChildren().add(actionButton("Download", () -> download(selected)));
            }
        });

        try {
            table.getItems().setAll(documentService.listDocuments(activeUser));
        } catch (Exception exception) {
            showInlineError(page, userMessage(exception));
        }
        page.getChildren().addAll(title, caption, table, details, abstractView, actions);
        root.setCenter(page);
    }

    private void showSubmitDocument() {
        if (activeUser.role() != User.Role.RESEARCHER) {
            showDocuments();
            return;
        }
        showShell();
        VBox page = new VBox(14);
        page.setMaxWidth(760);
        page.setPadding(new Insets(30));
        Label title = heading("Submit research");
        Label instructions = text("Upload a document for review. The assigned admin will receive an in-app notification.");
        instructions.setWrapText(true);
        TextField documentTitle = new TextField();
        documentTitle.setPromptText("Research title");
        styleField(documentTitle);
        TextArea abstractText = new TextArea();
        abstractText.setPromptText("Abstract (optional)");
        abstractText.setWrapText(true);
        abstractText.setPrefRowCount(5);
        TextField filePath = new TextField();
        filePath.setPromptText("Choose a document file");
        filePath.setEditable(false);
        styleField(filePath);
        Button chooseFile = secondaryButton("Browse");
        chooseFile.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose research document");
            java.io.File chosen = chooser.showOpenDialog(stage);
            if (chosen != null) {
                filePath.setText(chosen.getAbsolutePath());
            }
        });
        HBox filePicker = new HBox(9, filePath, chooseFile);
        HBox.setHgrow(filePath, Priority.ALWAYS);
        Label feedback = new Label();
        feedback.setWrapText(true);
        Button submit = primaryButton("Submit for review");
        submit.setOnAction(event -> {
            try {
                if (filePath.getText().isBlank()) {
                    throw new IllegalArgumentException("Choose the document file to submit.");
                }
                documentService.submitDocument(activeUser, documentTitle.getText(), abstractText.getText(),
                        Path.of(filePath.getText()));
                showAlert(Alert.AlertType.INFORMATION, "Submission received",
                        "Your document was submitted. You can follow its status in Documents and Notifications.");
                showDocuments();
            } catch (Exception exception) {
                feedback.setText(userMessage(exception));
                feedback.setStyle("-fx-text-fill: #B91C1C;");
            }
        });
        VBox card = new VBox(13, title, instructions, fieldLabel("TITLE"), documentTitle,
                fieldLabel("ABSTRACT"), abstractText, fieldLabel("DOCUMENT FILE"), filePicker, feedback, submit);
        card.setPadding(new Insets(26));
        card.setStyle(cardStyle());
        page.getChildren().add(card);
        root.setCenter(page);
    }

    private void showNotifications() {
        showShell();
        VBox page = new VBox(14);
        page.setPadding(new Insets(28));
        page.getChildren().addAll(heading("Notifications"),
                text("Private updates for " + activeUser.email() + "."));
        try {
            var notifications = documentService.listNotifications(activeUser);
            if (notifications.isEmpty()) {
                page.getChildren().add(text("You do not have any notifications yet."));
            }
            for (DocumentService.NotificationRecord notification : notifications) {
                Label message = new Label(notification.message());
                message.setWrapText(true);
                Label meta = new Label(notification.type().replace('_', ' ') + "  ·  "
                        + DATE_FORMAT.format(notification.createdAt().toInstant())
                        + (notification.read() ? "  ·  Read" : "  ·  New"));
                meta.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
                VBox note = new VBox(7, message, meta);
                note.setPadding(new Insets(16));
                note.setStyle(cardStyle() + (notification.read() ? "" : "-fx-border-color: #93C5FD;"));
                if (!notification.read()) {
                    Button markRead = linkButton("Mark as read");
                    markRead.setOnAction(event -> {
                        try {
                            documentService.markNotificationRead(activeUser, notification.id());
                            showNotifications();
                        } catch (Exception exception) {
                            showError(exception);
                        }
                    });
                    note.getChildren().add(markRead);
                }
                page.getChildren().add(note);
            }
        } catch (Exception exception) {
            showInlineError(page, userMessage(exception));
        }
        root.setCenter(page);
    }

    private void decide(DocumentService.DocumentRecord document, boolean approve) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(approve ? "Approve submission" : "Reject submission");
        dialog.setHeaderText(approve ? "Approve “" + document.title() + "”?" : "Reject “" + document.title() + "”? ");
        dialog.setContentText("Optional comments for the researcher:");
        Optional<String> comments = dialog.showAndWait();
        if (comments.isEmpty()) {
            return;
        }
        try {
            documentService.decide(activeUser, document.id(), approve, comments.get());
            showDocuments();
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void download(DocumentService.DocumentRecord document) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save research document");
        chooser.setInitialFileName(document.originalFileName());
        java.io.File destination = chooser.showSaveDialog(stage);
        if (destination == null) {
            return;
        }
        try {
            documentService.download(activeUser, document.id(), destination.toPath());
            showAlert(Alert.AlertType.INFORMATION, "Download complete",
                    "The document was saved to " + destination.getAbsolutePath());
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private TableColumn<DocumentService.DocumentRecord, String> stringColumn(
            String title, java.util.function.Function<DocumentService.DocumentRecord, String> value) {
        TableColumn<DocumentService.DocumentRecord, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private VBox metricCard(String title, int value, String description) {
        Label number = new Label(Integer.toString(value));
        number.setStyle("-fx-font-size: 30px; -fx-font-weight: 800; -fx-text-fill: " + NAVY + ";");
        Label caption = new Label(title);
        caption.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: " + BLUE + ";");
        Label hint = new Label(description);
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        VBox card = new VBox(8, caption, number, hint);
        card.setPadding(new Insets(20));
        card.setMinWidth(150);
        card.setStyle(cardStyle());
        return card;
    }

    private Button navButton(String label, Runnable action) {
        Button button = new Button(label);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setStyle("-fx-background-color: transparent; -fx-text-fill: " + INK
                + "; -fx-font-size: 13px; -fx-padding: 12 14 12 14; -fx-cursor: hand;");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Button primaryButton(String label) {
        Button button = new Button(label);
        button.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; -fx-font-weight: 700;"
                + " -fx-background-radius: 8; -fx-padding: 11 18 11 18; -fx-cursor: hand;");
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(label);
        button.setStyle("-fx-background-color: #E8EEFC; -fx-text-fill: " + NAVY
                + "; -fx-font-weight: 700; -fx-padding: 10 15 10 15; -fx-cursor: hand;");
        return button;
    }

    private Button actionButton(String label, Runnable action) {
        Button button = secondaryButton(label);
        button.setOnAction(event -> action.run());
        return button;
    }

    private Button linkButton(String label) {
        Button button = new Button(label);
        button.setStyle("-fx-background-color: transparent; -fx-text-fill: " + BLUE
                + "; -fx-font-weight: 700; -fx-underline: true; -fx-cursor: hand;");
        return button;
    }

    private Label heading(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 24px; -fx-font-weight: 800; -fx-text-fill: " + NAVY + ";");
        return label;
    }

    private Label text(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 13px; -fx-text-fill: " + MUTED + ";");
        return label;
    }

    private Label fieldLabel(String value) {
        Label label = new Label(value);
        label.setStyle("-fx-font-size: 10px; -fx-font-weight: 800; -fx-text-fill: " + MUTED + ";");
        return label;
    }

    private void styleField(TextField field) {
        field.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #DCE3ED; -fx-border-radius: 7;"
                + " -fx-background-radius: 7; -fx-padding: 11 12 11 12;");
    }

    private String cardStyle() {
        return "-fx-background-color: " + SURFACE + "; -fx-background-radius: 12;"
                + " -fx-border-color: #E2E8F0; -fx-border-radius: 12;";
    }

    private void showInlineError(VBox parent, String message) {
        Label error = new Label(message);
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #B91C1C;");
        parent.getChildren().add(error);
    }

    private String userMessage(Exception exception) {
        if (exception instanceof SQLException) {
            String detail = exception.getMessage();
            return "Database error. Confirm WampServer is running, the schema is imported, "
                    + "and BRAINHUB_DB_URL / BRAINHUB_DB_USER / BRAINHUB_DB_PASSWORD are correct."
                    + (detail == null || detail.isBlank() ? "" : " Details: " + detail);
        }
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "The operation could not be completed." : exception.getMessage();
    }

    private void showError(Exception exception) {
        showAlert(Alert.AlertType.ERROR, "BRAIN HUB", userMessage(exception));
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
