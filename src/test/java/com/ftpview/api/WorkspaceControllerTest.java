package com.ftpview.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.net.ftp.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceControllerTest {
    @TempDir Path dir;
    /** 本机列表隐藏点文件，并拒绝删除文件夹。 */
    @Test void localListingAndDeletion() throws Exception {
        WorkspaceController controller=new WorkspaceController();
        try {
            Files.writeString(dir.resolve("a.txt"),"keep");Files.writeString(dir.resolve(".hidden"),"secret");Files.createDirectory(dir.resolve("folder"));
            List<?> entries=(List<?>)controller.local(dir.toString()).get("files");assertEquals(2,entries.size());
            WorkspaceController.Request r=new WorkspaceController.Request();r.path=dir.toString();r.name="folder";
            assertThrows(IOException.class,()->controller.delete(r));r.name="../a.txt";assertThrows(IOException.class,()->controller.delete(r));
            r.name="a.txt";controller.delete(r);assertFalse(Files.exists(dir.resolve("a.txt")));
        }finally{controller.shutdown();}
    }
    /** 模拟 FTP 流验证完整下载，并确认同名文件不会被覆盖。 */
    @Test void downloadVerifiedAndNoOverwrite() throws Exception {
        FTPClient ftp=mock(FTPClient.class);byte[] content={0,1,2,(byte)255};
        FTPFile file=new FTPFile();file.setName("test.bin");file.setType(FTPFile.FILE_TYPE);file.setSize(content.length);
        when(ftp.changeWorkingDirectory("/")).thenReturn(true);when(ftp.listFiles()).thenReturn(new FTPFile[]{file});
        when(ftp.retrieveFileStream("test.bin")).thenAnswer(i->new ByteArrayInputStream(content));when(ftp.completePendingCommand()).thenReturn(true);
        WorkspaceController controller=new WorkspaceController(){@Override protected FTPClient connect(Config c){return ftp;}};
        try{
            WorkspaceController.Request r=new WorkspaceController.Request();r.name="test.bin";r.direction="download";r.remotePath="/";r.localPath=dir.toString();
            WorkspaceController.Job j=new WorkspaceController.Job();controller.transfer(j,r,new WorkspaceController.Config());
            assertEquals("completed",j.status);assertArrayEquals(content,Files.readAllBytes(dir.resolve("test.bin")));
            WorkspaceController.Job duplicate=new WorkspaceController.Job();controller.transfer(duplicate,r,new WorkspaceController.Config());assertEquals("failed",duplicate.status);assertArrayEquals(content,Files.readAllBytes(dir.resolve("test.bin")));
        }finally{controller.shutdown();}
    }
    /** 上传校验失败必须保留源文件，并禁止发布临时文件。 */
    @Test void corruptUploadIsNotPublished() throws Exception {
        Files.writeString(dir.resolve("test.txt"),"hello");FTPClient ftp=mock(FTPClient.class);
        when(ftp.changeWorkingDirectory("/")).thenReturn(true);when(ftp.makeDirectory(anyString())).thenReturn(true);
        when(ftp.storeFileStream(anyString())).thenReturn(new ByteArrayOutputStream());when(ftp.completePendingCommand()).thenReturn(true);
        when(ftp.retrieveFileStream(anyString())).thenReturn(new ByteArrayInputStream("wrong".getBytes()));
        WorkspaceController controller=new WorkspaceController(){@Override protected FTPClient connect(Config c){return ftp;}};
        try{WorkspaceController.Request r=new WorkspaceController.Request();r.name="test.txt";r.direction="upload";r.remotePath="/";r.localPath=dir.toString();
            WorkspaceController.Job j=new WorkspaceController.Job();controller.transfer(j,r,new WorkspaceController.Config());assertEquals("failed",j.status);verify(ftp,never()).rename(anyString(),anyString());assertEquals("hello",Files.readString(dir.resolve("test.txt")));
        }finally{controller.shutdown();}
    }
}
