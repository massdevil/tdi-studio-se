// ============================================================================
//
// Copyright (C) 2006-2013 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.repository.json.ui.wizards.buttons;

import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Composite;
import org.talend.repository.json.ui.wizards.AbstractJSONStepForm;
import org.talend.repository.json.ui.wizards.action.CreateJSONAttributeAction;
import org.talend.repository.json.ui.wizards.action.CreateJSONElementAction;
import org.talend.repository.json.ui.wizards.action.CreateJSONNameSpaceAction;
import org.talend.repository.ui.wizards.metadata.connection.files.xml.treeNode.Attribute;
import org.talend.repository.ui.wizards.metadata.connection.files.xml.treeNode.FOXTreeNode;
import org.talend.repository.ui.wizards.metadata.connection.files.xml.treeNode.NameSpaceNode;
import org.talend.repository.ui.wizards.metadata.connection.files.xml.view.AddElementDialog;

/**
 * DOC talend class global comment. Detailled comment
 */
public class AddTreeNodeButton extends AbstractTreeNodeButton {

    public AddTreeNodeButton(Composite parent, AbstractJSONStepForm form) {
        super(parent, form, "Add", null);
    }

    protected void handleSelectionEvent(TreeSelection selection) {
        if (form != null) {
            AddElementDialog dialog = new AddElementDialog(parent.getShell());
            if (dialog.open() == Window.CANCEL) {
                return;
            }
            if (AddElementDialog.CREATE_AS_SUBELEMENT.equals(dialog.getSelectValue())) {
                CreateJSONElementAction createElement = new CreateJSONElementAction(treeViewer, form, "");
                createElement.run();
            } else if (AddElementDialog.CREATE_AS_ATTRIBUTE.equals(dialog.getSelectValue())) {
                CreateJSONAttributeAction createAttr = new CreateJSONAttributeAction(treeViewer, form, "");
                createAttr.run();
            } else if (AddElementDialog.CREATE_AS_NAME_SPACE.equals(dialog.getSelectValue())) {
                CreateJSONNameSpaceAction createNameSpace = new CreateJSONNameSpaceAction(treeViewer, form, "");
                createNameSpace.run();
            }
            treeViewer.setSelection(selection);
        }
    }

    @Override
    protected void updateStatus(TreeSelection selection) {
        if (selection.getFirstElement() instanceof FOXTreeNode) {
            FOXTreeNode node = (FOXTreeNode) selection.getFirstElement();
            if (node == null) {
                getButton().setEnabled(false);
                return;
            }
            if (node instanceof Attribute) {
                getButton().setEnabled(false);
                return;
            }

            if (node instanceof NameSpaceNode) {
                getButton().setEnabled(false);
                return;
            }
            getButton().setEnabled(true);
        } else {
            getButton().setEnabled(false);
        }

    }

}