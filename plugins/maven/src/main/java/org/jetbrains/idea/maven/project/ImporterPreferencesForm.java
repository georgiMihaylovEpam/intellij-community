package org.jetbrains.idea.maven.project;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.idea.maven.core.util.Strings;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * @author Vladislav.Kaznacheev
 */
public class ImporterPreferencesForm {

  private JPanel panel;
  private JRadioButton myRegularModulesButton;
  private JRadioButton myTemporaryModulesButton;
  private JCheckBox myModuleDirCheckBox;
  private TextFieldWithBrowseButton myModuleDirControl;
  private JCheckBox myLookForNestedCheckBox;
  private JCheckBox myCreateGroupsCheckBox;
  private JCheckBox myUseMavenOutputCheckBox;
  private JTextArea myIgnoreDependenciesTextArea;
  private JPanel myIgnorePanel;

  public ImporterPreferencesForm() {
    this(false);
  }

  public ImporterPreferencesForm(boolean minimal) {

    ActionListener listener = new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        enableControls();
      }
    };
    myRegularModulesButton.addActionListener(listener);
    myTemporaryModulesButton.addActionListener(listener);
    myModuleDirCheckBox.addActionListener(listener);

    myModuleDirControl.addBrowseFolderListener(ProjectBundle.message("maven.import.title.module.dir"), "", null,
                                               new FileChooserDescriptor(false, true, false, false, false, false));

    if(minimal){
      myCreateGroupsCheckBox.setVisible(false);
      myUseMavenOutputCheckBox.setVisible(false);
      myIgnorePanel.setVisible(false);
      myIgnoreDependenciesTextArea.setVisible(false);
    }
  }

  private void enableControls() {
    final boolean dedicated = myModuleDirCheckBox.isSelected();
    myModuleDirControl.setEnabled(dedicated);
    if (dedicated && StringUtil.isEmptyOrSpaces(myModuleDirControl.getText())) {
      myModuleDirControl.setText(FileUtil.toSystemDependentName(getDefaultModuleDir()));
    }
  }

  public String getDefaultModuleDir() {
    return "";
  }

  public JComponent createComponent() {
    return panel;
  }

  public void getData(final MavenImporterPreferences data) {
    data.setUseTemporaryModules(myTemporaryModulesButton.isSelected());
    data.setDedicatedModuleDir(myModuleDirCheckBox.isSelected() ? myModuleDirControl.getText() : "");
    data.setCreateModuleGroups(myCreateGroupsCheckBox.isSelected());
    data.setLookForNested(myLookForNestedCheckBox.isSelected());
    data.setUseMavenOutput(myUseMavenOutputCheckBox.isSelected());
    data.setIgnoredDependencies(Strings.tokenize(myIgnoreDependenciesTextArea.getText(), Strings.WHITESPACE + ",;"));
  }

  public void setData(final MavenImporterPreferences data) {
    myRegularModulesButton.setSelected(!data.isUseTemporaryModules());
    myTemporaryModulesButton.setSelected(data.isUseTemporaryModules());

    myModuleDirCheckBox.setSelected(!StringUtil.isEmptyOrSpaces(data.getDedicatedModuleDir()));
    myModuleDirControl.setText(data.getDedicatedModuleDir());

    myCreateGroupsCheckBox.setSelected(data.isCreateModuleGroups());
    myLookForNestedCheckBox.setSelected(data.isLookForNested());
    myUseMavenOutputCheckBox.setSelected(data.isUseMavenOutput());
    myIgnoreDependenciesTextArea.setText(Strings.detokenize(data.getIgnoredDependencies(), ','));

    enableControls();
  }

  public boolean isModified(MavenImporterPreferences preferences) {
    MavenImporterPreferences formData = new MavenImporterPreferences();
    getData(formData);
    return !formData.equals(preferences);
  }
}
