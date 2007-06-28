/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

package net.sourceforge.transparent.Checkin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.ui.RefreshableOnComponent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import net.sourceforge.transparent.ClearCase;
import net.sourceforge.transparent.TransparentVcs;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * User: lloix
 * Date: Dec 6, 2006
 */
public class CCaseCheckinEnvironment implements CheckinEnvironment
{
  @NonNls private static final String CHECKIN_TITLE = "Check In";
  @NonNls private static final String SCR_TITLE = "SCR Number";

  private Project project;
  private TransparentVcs host;
  private double fraction;

  public CCaseCheckinEnvironment( Project project, TransparentVcs host )
  {
    this.project = project;
    this.host = host;
  }

  public RefreshableOnComponent createAdditionalOptionsPanel( CheckinProjectPanel panel )
  {
    @NonNls final JPanel additionalPanel = new JPanel();
    final JTextField scrNumber = new JTextField();

    additionalPanel.setLayout( new BorderLayout() );
    additionalPanel.add( new Label( SCR_TITLE ), "North" );
    additionalPanel.add( scrNumber, "Center" );

    scrNumber.addFocusListener( new FocusListener()
    {
        public void focusGained(FocusEvent e) {  scrNumber.selectAll();  }
        public void focusLost(FocusEvent focusevent) {}
    });

    return new RefreshableOnComponent()
    {
      public JComponent getComponent() {  return additionalPanel;  }

      public void saveState() { }
      public void restoreState() {  refresh();   }
      public void refresh() { }
    };
  }

  /**
   * Force to reuse the last checkout's comment for the checkin.
   */
  public String getDefaultMessageFor( FilePath[] filesToCheckin )
  {
    ClearCase cc = host.getClearCase();
    HashSet<String> commentsPerFile = new HashSet<String>();
    for( FilePath path : filesToCheckin )
    {
      String fileComment = cc.getCheckoutComment( new File( path.getPresentableUrl() ) );
      if( StringUtil.isNotEmpty( fileComment ) )
        commentsPerFile.add( fileComment );
    }

    StringBuilder overallComment = new StringBuilder();
    for( String comment : commentsPerFile )
    {
      overallComment.append( comment ).append( "\n-----" );
    }

    //  If Checkout comment is empty - return null, in this case <caller> will
    //  inherit last commit's message for this commit.
    return (overallComment.length() > 0) ? overallComment.toString() : null;
  }


  public boolean showCheckinDialogInAnyCase()   {  return false;  }
  public String  prepareCheckinMessage(String text)  {  return text;  }
  public String  getHelpId() {  return null;   }
  public String  getCheckinOperationName() {  return CHECKIN_TITLE;  }

  public List<VcsException> commit( List<Change> changes, String comment )
  {
    List<VcsException> errors = new ArrayList<VcsException>();
    HashSet<FilePath> processedFiles = new HashSet<FilePath>();
    comment = comment.replace( "\"", "\\\"" );

    clearTemporaryStatuses( changes );

    adjustChangesWithRenamedParentFolders( changes );

    try
    {
      initProgress( changes.size() );

      //  Committing of renamed folders must be performed first since they
      //  affect all other checkings under them (except those having status
      //  "ADDED") since:
      //  - if modified file is checked in before renamed folder checkin then
      //    we need to checkin from (yet) nonexisting file into (already) non-
      //    existing space. It is too tricky to recreate the old folders
      //    structure and commit from out of there.
      //  - if modified file is checked AFTER the renamed folder has been
      //    checked in, we just have to checkin in into the necessary place,
      //    just get the warning that we checking in file which was checked out
      //    from another location. Supress it.
      commitRenamedFolders( changes, comment, errors );

      commitNew( changes, comment, processedFiles, errors );
      commitDeleted( changes, comment, errors );
      commitChanged( changes, comment, processedFiles, errors );
    }
    catch( ProcessCanceledException e )
    {
      //  Nothing to do, just refresh the files which are already committed.
    }

    VcsUtil.refreshFiles( project, processedFiles );

    return errors;
  }

  /**
   * Before new commit, clear all "Merge Conflict" statuses on files set on
   * them since the last commit. 
   */
  private static void clearTemporaryStatuses( List<Change> changes )
  {
    for( Change change : changes )
    {
      ContentRevision rev = change.getAfterRevision();
      if( rev != null )
      {
        FilePath filePath = rev.getFile();
        VirtualFile file = filePath.getVirtualFile();
        if( file != null ) //  e.g. for deleted files
          file.putUserData( TransparentVcs.MERGE_CONFLICT, null );
      }
    }
  }

  private void adjustChangesWithRenamedParentFolders( List<Change> changes )
  {
    Set<VirtualFile> renamedFolders = getNecessaryRenamedFoldersForList( changes );
    if( renamedFolders.size() > 0 )
    {
      for( VirtualFile folder : renamedFolders )
        changes.add( ChangeListManager.getInstance( project ).getChange( folder ) );
    }
  }

  private void commitRenamedFolders( List<Change> changes, String comment, List<VcsException> errors )
  {
    for (Change change : changes)
    {
      if( VcsUtil.isRenameChange( change ) && VcsUtil.isChangeForFolder( change ) )
      {
        FilePath newFile = change.getAfterRevision().getFile();
        FilePath oldFile = change.getBeforeRevision().getFile();

        host.renameAndCheckInFile( oldFile.getIOFile(), newFile.getName(), comment, errors );
        host.renamedFolders.remove( newFile.getPath() );
        incrementProgress( newFile.getPath() );
      }
    }
  }

  private void commitNew( List<Change> changes, String comment,
                          HashSet<FilePath> processedFiles, List<VcsException> errors )
  {
    HashSet<FilePath> folders = new HashSet<FilePath>();
    HashSet<FilePath> files = new HashSet<FilePath>();

    collectNewFilesAndFolders( changes, processedFiles, folders, files );
    commitFoldersAndFiles( folders, files, comment, errors );
  }

  private void collectNewFilesAndFolders( List<Change> changes, HashSet<FilePath> processedFiles,
                                          HashSet<FilePath> folders, HashSet<FilePath> files )
  {
    for( Change change : changes )
    {
      if( VcsUtil.isChangeForNew( change ) )
      {
        FilePath filePath = change.getAfterRevision().getFile();
        if( filePath.isDirectory() )
          folders.add( filePath );
        else
        {
          files.add( filePath );
          analyzeParent( filePath, folders );
        }
      }
    }
    processedFiles.addAll( folders );
    processedFiles.addAll( files );
  }

  /**
   *  Add all folders first, then add all files into these folders.
   *  Difference between added and modified files is that added file
   *  has no "before" revision.
   */
  private void commitFoldersAndFiles( HashSet<FilePath> folders, HashSet<FilePath> files,
                                      String comment, List<VcsException> errors )
  {
    FilePath[] foldersSorted = folders.toArray( new FilePath[ folders.size() ] );
    foldersSorted = VcsUtil.sortPathsFromOutermost( foldersSorted );

    for( FilePath folder : foldersSorted )
      host.addFile( folder.getVirtualFile(), comment, errors );

    for( FilePath file : files )
    {
      host.addFile( file.getVirtualFile(), comment, errors );
      if( host.getConfig().useUcmModel )
      {
        //  If the file was checked out using one view's activity but is then
        //  moved to another changelist (activity) we must issue "chactivity"
        //  command for the file element so that subsequent "checkin" command
        //  behaves as desired.
        String activity = host.getActivityOfViewOfFile( file );
        String currentActivity = getChangeListName( file.getVirtualFile() );
        if(( activity != null ) && !activity.equals( currentActivity ) )
        {
          host.changeActivityForLastVersion( file, activity, currentActivity, errors );
        }
      }
      incrementProgress( file.getPath() );
    }
  }

  /**
   * If the parent of the file has status New or Unversioned - add it
   * to the list of folders OBLIGATORY for addition into the repository -
   * no file can be added into VSS without all higher folders are already
   * presented there.
   * Process with the parent's parent recursively.
   */
  private void analyzeParent( FilePath file, HashSet<FilePath> folders )
  {
    VirtualFile parent = file.getVirtualFileParent();
    FileStatus status = FileStatusManager.getInstance( project ).getStatus( parent );
    if( status == FileStatus.ADDED || status == FileStatus.UNKNOWN )
    {
      FilePath parentPath = file.getParentPath();
      folders.add( parentPath );
      analyzeParent( parentPath, folders );
    }
  }

  private void commitDeleted( List<Change> changes, String comment, List<VcsException> errors )
  {
    for( Change change : changes )
    {
      if( VcsUtil.isChangeForDeleted( change ) )
      {
        final FilePath fp = change.getBeforeRevision().getFile();
        host.removeFile( fp.getIOFile(), comment, errors );

        String path = VcsUtil.getCanonicalLocalPath( fp.getPath() );
        host.deletedFiles.remove( path );
        host.deletedFolders.remove( path );

        incrementProgress( fp.getPath() );
        ApplicationManager.getApplication().invokeLater( new Runnable() {
          public void run() { VcsDirtyScopeManager.getInstance( project ).fileDirty( fp );  }
        });
      }
    }
  }

  private void commitChanged( List<Change> changes, String comment,
                              HashSet<FilePath> processedFiles, List<VcsException> errors )
  {
    for( Change change : changes )
    {
      if( !VcsUtil.isChangeForNew( change ) &&
          !VcsUtil.isChangeForDeleted( change ) &&
          !VcsUtil.isChangeForFolder( change ) )
      {
        FilePath file = change.getAfterRevision().getFile();
        String newPath = file.getPath();
        String oldPath = host.renamedFiles.get( newPath );
        if( oldPath != null )
        {
          FilePath oldFile = change.getBeforeRevision().getFile();

          //  If parent folders' names of the revisions coinside, then we
          //  deal with the simle rename, otherwise we process full-scaled
          //  file movement across folders (packages).

          if( oldFile.getVirtualFileParent().getPath().equals( file.getVirtualFileParent().getPath() ))
          {
            host.renameAndCheckInFile( oldFile.getIOFile(), file.getName(), comment, errors );
          }
          else
          {
            String newFolder = file.getVirtualFileParent().getPath();
            host.moveRenameAndCheckInFile( oldPath, newFolder, file.getName(), comment, errors );
          }
          host.renamedFiles.remove( newPath );
        }
        else
        {
          host.checkinFile( file, comment, errors );
          if( host.getConfig().useUcmModel )
          {
            //  If the file was checked out using one view's activity but has then
            //  been moved to another changelist (activity) we must issue "chactivity"
            //  command for the file element so that subsequent "checkin" command
            //  behaves as desired.
            String activity = host.getCheckoutActivityForFile( file.getPath() );
            String currentActivity = getChangeListName( file.getVirtualFile() );
            if(( activity != null ) && !activity.equals( currentActivity ) )
            {
              host.changeActivityForLastVersion( file, activity, currentActivity, errors );
            }
          }
        }

        processedFiles.add( file );
        incrementProgress( file.getPath() );
      }
    }
  }

  /**
   * Commit local deletion of files and folders to VCS.
   */
  public List<VcsException> scheduleMissingFileForDeletion( List<FilePath> paths )
  {
    List<VcsException> errors = new ArrayList<VcsException>();
    for( FilePath file : paths )
    {
      String path = VcsUtil.getCanonicalLocalPath( file.getPath() );
      if( host.removedFiles.contains( path ) || host.removedFolders.contains( path ) )
      {
        host.removeFile( file.getIOFile(), null, errors );
      }

      host.removedFiles.remove( path );
      host.removedFolders.remove( path );
    }
    return errors;
  }

  public List<VcsException> scheduleUnversionedFilesForAddition( List<VirtualFile> files )
  {
    for( VirtualFile file : files )
    {
      host.add2NewFile( file.getPath() );
      VcsUtil.markFileAsDirty( project, file );

      //  Extend status change to all parent folders if they are not
      //  included into the context of the menu action.
      extendStatus( file );
    }
    // Keep intentionally empty.
    return new ArrayList<VcsException>();
  }

  private void extendStatus( VirtualFile file )
  {
    FileStatusManager mgr = FileStatusManager.getInstance( project );
    VirtualFile parent = file.getParent();

    if( mgr.getStatus( parent ) == FileStatus.UNKNOWN )
    {
      host.add2NewFile( parent );
      VcsUtil.markFileAsDirty( project, parent );

      extendStatus( parent );
    }
  }
  
  private Set<VirtualFile> getNecessaryRenamedFoldersForList( List<Change> changes )
  {
    Set<VirtualFile> set = new HashSet<VirtualFile>();
    for( Change change : changes )
    {
      ContentRevision rev = change.getAfterRevision();
      if( rev != null )
      {
        for( String newFolderName : host.renamedFolders.keySet() )
        {
          if( rev.getFile().getPath().startsWith( newFolderName ) )
          {
            VirtualFile parent = VcsUtil.getVirtualFile( newFolderName );
            set.add( parent );
          }
        }
      }
    }
    for( Change change : changes )
    {
      ContentRevision rev = change.getAfterRevision();
      if( rev != null )
      {
        VirtualFile submittedParent = rev.getFile().getVirtualFile();
        if( submittedParent != null )
          set.remove( submittedParent );
      }
    }

    return set;
  }

  @Nullable
  private String getChangeListName( VirtualFile file )
  {
    String changeListName = null;

    ChangeListManager mgr = ChangeListManager.getInstance( project );
    Change change = mgr.getChange( file );
    if( change != null )
    {
      changeListName = mgr.getChangeList( change ).getName();
    }

    return changeListName;
  }
  
  private void initProgress( int total )
  {
    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
    if( progress != null )
    {
      fraction = 1.0 / (double) total;
      progress.setIndeterminate( false );
      progress.setFraction( 0.0 );
    }
  }

  private void incrementProgress( String text ) throws ProcessCanceledException
  {
    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
    if( progress != null )
    {
      double newFraction = progress.getFraction();
      newFraction += fraction;
      progress.setFraction( newFraction );
      progress.setText( text );

      if( progress.isCanceled() )
        throw new ProcessCanceledException();
    }
  }
}