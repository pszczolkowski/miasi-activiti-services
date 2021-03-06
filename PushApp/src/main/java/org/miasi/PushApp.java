package org.miasi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.Header;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.miasi.config.Config;
import org.miasi.exception.ActivityException;
import org.miasi.exception.GitException;
import org.miasi.model.Task;
import org.unbescape.uri.UriEscape;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PushApp {

    private JPanel jPanel;
    private JComboBox<Task> tasksCombo;
    private JButton pushButton;
    private JTextArea statusArea;
    private JButton refreshButton;
    private JScrollPane scrollArea;
    private JTextField githubLogin;
    private JPasswordField githubPassword;

    public static void main(String[] args) {
        PushApp pushApp = new PushApp();

        JFrame frame = new JFrame("PushApp");
        frame.setContentPane(pushApp.jPanel);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

//        try {
//            UIManager.setLookAndFeel(WindowsLookAndFeel.class.getName());
//        } catch (ClassNotFoundException | InstantiationException |
//                UnsupportedLookAndFeelException | IllegalAccessException ignored) {
//        }

        pushApp.init();
    }

    private Config config;

    public PushApp() {
        log("App started. Please wait. Initializing ...");

        refreshButton.setEnabled(false);
        pushButton.setEnabled(false);
        statusArea.setEditable(false);
    }

    // --------------------------------------------------------------------------------------------

    public void init() {
        init$actionListeners();

        try {
            init$config();
        } catch (Exception ex) {
            init$error("CONFIG ERROR", "Error!\n" +
                    "Config file doesn't exist or has bad structure.\n" +
                    "Please fix it and rebuild app.");
            return;
        }

        try {
            init$verifyRepo();
        } catch (Exception e) {
            File repo = init$gitRepo();
            init$error("CONFIG ERROR", "Error!\n" +
                    "Unable to verify git repo.\n" +
                    "Configured repo path: " + repo.getPath() + "\n" +
                    "Configured repo path (absolute): " + repo.getAbsolutePath() + "\n" +
                    "Double check if: \n" +
                    "- given path is git repo,\n" +
                    "- remote url is specified.\n" +
                    "Please fix it and rebuild/restart app.");
            return;
        }

        action$refreshTasks();
    }

    // --------------------------------------------------------------------------------------------

    public void init$actionListeners() {
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        action$onRefreshButtonClicked();
                    }
                }).start();
            }
        });

        pushButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        action$onPushButtonClicked();
                    }
                }).start();
            }
        });
        tasksCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        action$onTaskSelected();
                    }
                }).start();
            }
        });
    }

    public void init$config() throws Exception {
        config = Config.readFromConfigFile();
    }

    public File init$gitRepo() {
        return new File(config.getGitPath());
    }

    public void init$verifyRepo() throws Exception {
        try (Git git = Git.open(init$gitRepo())) {
            if (git.remoteList().call().isEmpty()) {
                throw new GitException("no remote found");
            }
        }
    }

    public void init$error(String type, String msg) {
        pushButton.setEnabled(true);
        pushButton.setText(type);
        statusArea.append(msg);
        JOptionPane.showMessageDialog(null, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    // --------------------------------------------------------------------------------------------

    public void action$onRefreshButtonClicked() {
        log("Clicked: refresh");
        action$refreshTasks();
    }

    public void action$onPushButtonClicked() {
        log("Clicked: push");
        action$push();
    }

    public void action$onTaskSelected() {
        log("Task selected, item=", tasksCombo.getSelectedItem());
    }

    public void action$refreshTasks() {
        log("Action-refresh: start.");

        try {
            refreshButton.setEnabled(false);
            pushButton.setEnabled(false);

            List<Task> tasks = activity$getPushTasks();
            Task[] tasksArray = tasks.toArray(new Task[tasks.size()]);
            ComboBoxModel<Task> taskModel = new DefaultComboBoxModel<>(tasksArray);
            tasksCombo.setModel(taskModel);

            if (!tasks.isEmpty()) {
                log("Task selected, item=", tasksCombo.getSelectedItem());
                pushButton.setEnabled(true);
            }

            log("Action-refresh: done.");

        } catch (Exception ex) {
            log("Exception. ", ExceptionUtils.getStackTrace(ex));
            log("Something went wrong. Try refresh tasks.");

        } finally {
            refreshButton.setEnabled(true);
        }
    }

    public void action$push() {
        log("Action-push: start.");

        try {
            refreshButton.setEnabled(false);
            pushButton.setEnabled(false);

            Task task = (Task) tasksCombo.getSelectedItem();

            git$push();
            activity$completeTask(task.getId());
            action$refreshTasks();

            log("Action-push: done.");
            JOptionPane.showMessageDialog(null,
                    "Successfully done.",
                    "OK", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            log("Exception. ", ExceptionUtils.getStackTrace(ex));
            log("Something went wrong. Maybe try again?");

        } finally {
            refreshButton.setEnabled(true);
            if (tasksCombo.getModel().getSize() > 0) {
                pushButton.setEnabled(true);
            }
        }
    }

    // --------------------------------------------------------------------------------------------

    public static Header activity$getAuthHeader(Config config) {
        String activityBasic = config.getActivityUsername() + ":" + config.getActivityPassword();
        String activityBasic64 = BaseEncoding.base64().encode(activityBasic.getBytes());
        String headerVal = "Basic " + activityBasic64;
        return new BasicHeader("Authorization", headerVal);
    }

    public List<Task> activity$getPushTasks() throws ActivityException {
        log("Activity: retrieving tasks ...");

        String taskInfoUrl = String.format(
                "%s/service/runtime/tasks?taskDefinitionKey=%s",
                config.getActivityRestUrl(),
                UriEscape.escapeUriQueryParam(config.getActivityPushTaskId()));

        try {
            String content = Request.Get(taskInfoUrl)
                    .addHeader(activity$getAuthHeader(config))
                    .execute().returnContent().asString();

            JSONObject taskInfo = new JSONObject(content);
            JSONArray data = taskInfo.getJSONArray("data");

            List<Task> tasks = new ArrayList<>();

            for (int i = 0; i < data.length(); i++) {
                log("Activity: retrieving tasks ... ", i + 1, "/", data.length());

                Task task = new Task();
                JSONObject taskObject = data.getJSONObject(i);

                task.setId(taskObject.getString("id"));
                task.setDeveloper(taskObject.getString("assignee"));
                task.setName(activity$getTaskName(task.getId()));
                tasks.add(task);
            }

            log("Activity: retrieving tasks ... done");
            return tasks;

        } catch (Exception e) {
            throw new ActivityException(e);
        }
    }

    public String activity$getTaskName(String taskId) throws ActivityException {
        log("Activity: retrieving task name ..., id=", taskId);

        String taskVars = String.format(
                "%s/service/runtime/tasks/%s/variables",
                config.getActivityRestUrl(),
                UriEscape.escapeUriPathSegment(taskId));

        try {
            String content = Request.Get(taskVars)
                    .addHeader(activity$getAuthHeader(config))
                    .execute().returnContent().asString();

            JSONArray data = new JSONArray(content);

            for (int i = 0; i < data.length(); i++) {
                JSONObject object = data.getJSONObject(i);
                if (object.getString("name").equals("task_name")) {
                    log("Activity: retrieving task name ... done, id=", taskId);
                    return object.getString("value");
                }
            }

            throw new IllegalStateException("no such task");

        } catch (Exception e) {
            throw new ActivityException(e);
        }
    }

    public void activity$completeTask(String taskId) throws ActivityException {
        log("Activity: completing task ..., id=", taskId);

        String taskUrl = String.format(
                "%s/service/runtime/tasks/%s",
                config.getActivityRestUrl(),
                UriEscape.escapeUriPathSegment(taskId));

        try {
            Map<String, ?> param = ImmutableMap.of(
                    "action", "complete",
                    "variables", new Object[]{});
            String paramJson = toJson(Map.class, param);

            Request.Post(taskUrl)
                    .addHeader(activity$getAuthHeader(config))
                    .bodyString(paramJson, ContentType.APPLICATION_JSON)
                    .execute().returnContent().asString();

            log("Activity: completing task ... done, id=", taskId);
        } catch (Exception e) {
            throw new ActivityException(e);
        }
    }

    // --------------------------------------------------------------------------------------------

    public void git$push() throws GitException {
        log("Git: pushing to remote ...");

        CredentialsProvider cred = new UsernamePasswordCredentialsProvider(
                githubLogin.getText(), githubPassword.getPassword()
        );

        try (Git git = Git.open(init$gitRepo())) {
            git.push().setCredentialsProvider(cred).call();

            log("Git: pushing to remote ... done");
        } catch (Exception e) {
            throw new GitException(e);
        }
    }

    // --------------------------------------------------------------------------------------------

    public void log(Object... str) {
        for (Object o : str) {
            statusArea.append(o.toString());

        }
        statusArea.append("\n");

        try {
            statusArea.setCaretPosition(statusArea.getLineStartOffset(statusArea.getLineCount() - 1));
        } catch (BadLocationException ignored) {
        }
    }

    // --------------------------------------------------------------------------------------------

    public static <T> String toJson(Class<T> clazz, T obj) throws IOException {
        try {
            return new ObjectMapper().writerFor(clazz).writeValueAsString(obj);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
