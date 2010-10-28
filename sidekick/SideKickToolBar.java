package sidekick;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.ArrayList;

import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.Timer;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;

import org.gjt.sp.jedit.EditBus;
import org.gjt.sp.jedit.EditBus.EBHandler;
import org.gjt.sp.jedit.EditPane;
import org.gjt.sp.jedit.msg.EditPaneUpdate;
import org.gjt.sp.jedit.msg.PropertiesChanged;
import org.gjt.sp.jedit.textarea.JEditTextArea;
import org.gjt.sp.jedit.textarea.Selection;
import org.gjt.sp.jedit.View;
import org.gjt.sp.jedit.jEdit;

@SuppressWarnings("serial")
public class SideKickToolBar extends JToolBar
{
	private View view;
	private JComboBox combo;
	private boolean followCaret;
	private CaretListener caretListener;
	private Timer caretTimer;
	private SideKickParsedData data;
	private boolean automaticUpdate = false;
	private int delayMs;

	public SideKickToolBar(View view)
	{
		this.view = view;
		setFloatable(false);
		combo = new JComboBox();
		combo.setRenderer(new ComboCellRenderer());
		combo.addItemListener(new ItemListener()
		{
			public void itemStateChanged(ItemEvent e)
			{
				if (automaticUpdate)
					return;
				if (e.getStateChange() != ItemEvent.SELECTED)
					return;
				Object o = e.getItem();
				if (! (o instanceof NodeWrapper))
					return;
				Asset asset = ((NodeWrapper) o).asset;
				if (asset == null)
					return;
				SideKickToolBar.this.view.getTextArea().setCaretPosition(
					asset.start.getOffset());
			}
		});
		add(combo);
		update();
		followCaret = SideKick.isFollowCaret();
		delayMs = jEdit.getIntegerProperty("sidekick.toolBarUpdateDelay", 200);
		if (followCaret)
			addCaretListener();
		EditBus.addToBus(this);
	}
	@EBHandler
	public void handleSideKickUpdate(SideKickUpdate msg)
	{
		if(msg.getView() == view)
			update();
	}
	@EBHandler
	public void handlePropertiesChanged(PropertiesChanged msg)
	{
		delayMs = jEdit.getIntegerProperty("sidekick.toolBarUpdateDelay", 200);
		boolean newFollowCaret = SideKick.isFollowCaret();
		if (newFollowCaret != followCaret)
		{
			followCaret = newFollowCaret;
			if (followCaret)
				addCaretListener();
			else
				removeCaretListener();
		}
	}
	@EBHandler
	public void handleEditPaneUpdate(EditPaneUpdate epu)
	{
		if (! followCaret)
			return;
		EditPane editPane = epu.getEditPane();
		if (epu.getWhat() == EditPaneUpdate.CREATED)
			editPane.getTextArea().addCaretListener(caretListener);
		else if (epu.getWhat() == EditPaneUpdate.DESTROYED)
			editPane.getTextArea().removeCaretListener(caretListener);
	}
	private void addCaretListener()
	{
		caretListener = new CaretHandler();
		for (EditPane ep: view.getEditPanes())
			ep.getTextArea().addCaretListener(caretListener);
	}
	private void removeCaretListener()
	{
		for (EditPane ep: view.getEditPanes())
			ep.getTextArea().removeCaretListener(caretListener);
		caretListener = null;
	}
	private void update()
	{
		automaticUpdate = true;
		data = SideKickParsedData.getParsedData(view);
		combo.removeAllItems();
		if (data == null)
			combo.addItem(jEdit.getProperty("sidekick-tree.not-parsed"));
		else
			addTree(data.root, null);
		automaticUpdate = false;
	}
	private void addTree(TreeNode node, NodeWrapper parent)
	{
		// Always insert children only
		for (int i = 0; i < node.getChildCount(); i++)
		{
			TreeNode child = node.getChildAt(i);
			NodeWrapper nw = new NodeWrapper(parent, child);
			if (nw.isAsset())
				combo.addItem(nw);
			addTree(child, nw);
		}
	}

	public void dispose()
	{
		EditBus.removeFromBus(this);
	}

	private void selectItemAtPosition(int position)
	{
		NodeWrapper selected = null;
		for (int i = 0; i < combo.getItemCount(); i++)
		{
			NodeWrapper nw = (NodeWrapper) combo.getItemAt(i);
			if (nw == null)
				continue;
			if (nw.contains(position))
			{
				if (nw.isBetterThan(selected))
					selected = nw;
			}
		}
		if (selected != null)
		{
			automaticUpdate = true;
			combo.setSelectedItem(selected);
			automaticUpdate = false;
		}
	}

	private class CaretHandler implements CaretListener
	{
		public void caretUpdate(CaretEvent e)
		{
			if (e.getSource() != view.getTextArea())
				return;
			if (caretTimer != null)
			{
				caretTimer.stop();
			}
			else
			{
				caretTimer = new Timer(0,new ActionListener()
				{
					public void actionPerformed(ActionEvent evt)
					{
						JEditTextArea textArea = view.getTextArea();
						int caret = textArea.getCaretPosition();
						Selection s = textArea.getSelectionAtOffset(caret);
						selectItemAtPosition(s == null ? caret : s.getStart());
					}
				});
				caretTimer.setRepeats(false);
			}
			caretTimer.setInitialDelay(delayMs);
			caretTimer.start();
		}
	}

	private static class NodeWrapper
	{
		public NodeWrapper parent;
		public String str;
		public Icon icon;
		public Asset asset;
		public NodeWrapper(NodeWrapper parent, TreeNode node)
		{
			this.parent = parent;
			if (node instanceof DefaultMutableTreeNode)
			{
				DefaultMutableTreeNode dmtn = (DefaultMutableTreeNode) node;
				Object o = dmtn.getUserObject();
				if (o == null)
					str = "";
				else
				{
					if (o instanceof Asset)
					{
						asset = (Asset) o;
						icon = asset.getIcon();
						str = asset.getShortString();
					}
					else
						str = o.toString();
				}
			}
		}
		public boolean contains(int position)
		{
			return (asset != null && asset.getStart().getOffset() <= position &&
				asset.getEnd().getOffset() > position);
		}
		public boolean isBetterThan(NodeWrapper other)
		{
			return (other == null ||
				asset.getStart().getOffset() > other.asset.getStart().getOffset() ||
				asset.getEnd().getOffset() < other.asset.getEnd().getOffset());
		}
		public boolean isAsset()
		{
			return (asset != null);
		}
		public void addLabel(JPanel p)
		{
			if (parent != null)
				parent.addLabel(p);
			JLabel l = new JLabel();
			l.setText(str);
			if (icon != null)
				l.setIcon(icon);
			p.add(l);
		}
	}

	private static class ComboCellRenderer extends DefaultListCellRenderer
	{
		public Component getListCellRendererComponent(JList list, Object value,
				int index, boolean isSelected, boolean cellHasFocus)
		{
			JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
			if (value instanceof NodeWrapper)
			{
				NodeWrapper nw = (NodeWrapper) value;
				nw.addLabel(p);
			}
			return p;
		}
	}

}
