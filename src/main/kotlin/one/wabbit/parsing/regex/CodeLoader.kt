package one.wabbit.parsing.regex

import java.security.SecureClassLoader

class CodeLoader : SecureClassLoader() {
    fun load(name: String, data: ByteArray): Class<*> {
        return defineClass(name, data, 0, data.size)
    }
}
