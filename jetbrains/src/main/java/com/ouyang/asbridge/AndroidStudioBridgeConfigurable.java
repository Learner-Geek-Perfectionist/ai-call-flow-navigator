package com.ouyang.asbridge;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Objects;

public final class AndroidStudioBridgeConfigurable implements SearchableConfigurable {
    private final Project project;
    private JPanel panel;
    private JCheckBox enabledField;
    private JCheckBox focusEditorField;
    private JTextField projectRootField;
    private JTextField portField;

    public AndroidStudioBridgeConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getId() {
        return "android.studio.bridge";
    }

    @Override
    public @Nls String getDisplayName() {
        return "Android Studio Bridge";
    }

    @Override
    public JComponent createComponent() {
        enabledField = new JCheckBox("Enable local bridge", true);
        focusEditorField = new JCheckBox("Focus editor after opening source", false);
        projectRootField = new JTextField();
        portField = new JTextField();

        panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1.0;
        panel.add(enabledField, constraints);

        constraints.gridy = 1;
        panel.add(focusEditorField, constraints);

        addRow(2, "Project root", projectRootField);
        addRow(3, "Port", portField);
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        AndroidStudioBridgeSettings.SettingsState state = settings().getState();
        return enabledField.isSelected() != state.enabled
                || focusEditorField.isSelected() != state.focusEditor
                || !Objects.equals(projectRootField.getText().trim(), nullToEmpty(state.projectRoot))
                || !Objects.equals(portField.getText().trim(), Integer.toString(state.port));
    }

    @Override
    public void apply() throws ConfigurationException {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException error) {
            throw new ConfigurationException("Port must be a number");
        }
        if (port < 1024 || port > 65535) {
            throw new ConfigurationException("Port must be between 1024 and 65535");
        }

        AndroidStudioBridgeSettings.SettingsState state = settings().getState();
        state.enabled = enabledField.isSelected();
        state.focusEditor = focusEditorField.isSelected();
        state.projectRoot = projectRootField.getText().trim();
        state.port = port;
        project.getService(AndroidStudioBridgeProjectService.class).restart();
    }

    @Override
    public void reset() {
        AndroidStudioBridgeSettings.SettingsState state = settings().getState();
        enabledField.setSelected(state.enabled);
        focusEditorField.setSelected(state.focusEditor);
        projectRootField.setText(nullToEmpty(state.projectRoot));
        portField.setText(Integer.toString(state.port));
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        enabledField = null;
        focusEditorField = null;
        projectRootField = null;
        portField = null;
    }

    private AndroidStudioBridgeSettings settings() {
        return AndroidStudioBridgeSettings.getInstance(project);
    }

    private void addRow(int row, String label, JTextField field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.insets = new Insets(4, 4, 4, 4);
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.insets = new Insets(4, 4, 4, 4);
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, fieldConstraints);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
