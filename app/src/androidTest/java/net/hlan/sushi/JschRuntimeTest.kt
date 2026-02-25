package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jcraft.jsch.JSch
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JschRuntimeTest {
    @Test
    fun jschJceClassesPresent() {
        Class.forName("com.jcraft.jsch.jce.Random")
        Class.forName("com.jcraft.jsch.jce.AES128CTR")
        Class.forName("com.jcraft.jsch.jce.SHA256")

        val jsch = JSch()
        assertNotNull(jsch)
    }
}
