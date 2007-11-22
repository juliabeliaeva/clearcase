package net.sourceforge.transparent.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFile;
import net.sourceforge.transparent.TransparentVcs;
import org.jetbrains.annotations.NonNls;

public class AddAction extends SynchronousAction
{
  @NonNls private final static String ACTION_NAME = "Add File";

  protected String getActionName( AnActionEvent e ) { return ACTION_NAME; }

  protected boolean isEnabled( VirtualFile file, AnActionEvent e )
  {
    return getFileStatus( getProject( e ), file ) == FileStatus.UNKNOWN;
  }

  protected void perform( VirtualFile file, AnActionEvent e )
  {
    //  Perform only moving the file into normal changelist with the
    //  proper status "ADDED". After that the file can be submitted into
    //  the repository via "Commit" dialog.
    TransparentVcs.getInstance( _actionProjectInstance ).add2NewFile( file );

    VcsDirtyScopeManager mgr = VcsDirtyScopeManager.getInstance( _actionProjectInstance );
    mgr.fileDirty( file );
    file.refresh( true, true );
  }
}
