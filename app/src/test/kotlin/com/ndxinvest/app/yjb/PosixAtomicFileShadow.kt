package com.ndxinvest.app.yjb

import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Windows host renameTo cannot replace an existing file, unlike Android/Linux rename(2).
 * Emulate only that filesystem difference; cipher, codec, real read and recovery stay exercised.
 * A separate real-device test exercises the actual Android AtomicFile replacement.
 */
@Implements(AtomicFile::class)
class PosixAtomicFileShadow {
    @RealObject lateinit var file:AtomicFile
    @Implementation fun finishWrite(stream:FileOutputStream?) {
        stream?.fd?.sync();stream?.close()
        Files.move(java.io.File(file.baseFile.path+".new").toPath(),file.baseFile.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
    }
}
