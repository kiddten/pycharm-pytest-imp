package com.y4kstudios.pytestimp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.ini4j.Ini
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import java.nio.file.Paths


@State(name = "PyTestImpService", storages = [Storage("other.xml")])
class PyTestImpService(val project: Project) : PersistentStateComponent<PyTestImpService.State> {
    companion object {
        fun getInstance(project: Project): PyTestImpService =
            ServiceManager.getService(project, PyTestImpService::class.java)

        fun getAllInstances(): List<PyTestImpService> =
            ProjectManager.getInstance().openProjects.map { getInstance(it) }

    }

    class State {
        var pytestIniPath: String = "pytest.ini"
    }

    private var myState = State()

    override fun getState(): State = this.myState
    override fun loadState(state: State) {
        pytestConfigPath = state.pytestIniPath
    }

    var pytestConfigPath: String
        get() = myState.pytestIniPath
        set(value) {
            myState.pytestIniPath = value

            LocalFileSystem.getInstance().findFileByNioFile(Paths.get(value)).let {
                refreshPytestConfig(it)
            }
        }

    var pytestConfig: PyTestConfig? = PyTestConfig.parse(null)

    fun refreshPytestConfig(file: VirtualFile?) {
        val newPytestConfig = PyTestConfig.parse(file)

        if (newPytestConfig != pytestConfig) {
            pytestConfig = newPytestConfig

            // TODO: make more specific
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}


/**
 * Listen for filesystem changes, and refresh PyTestImpService.pytestIni on
 * changes to the configured pytest.ini
 */
class PyTestIniListener : BulkFileListener {
    override fun after(events: MutableList<out VFileEvent>) {
        val pytestConfigs = PyTestImpService.getAllInstances().groupBy({ it.pytestConfigPath }, { it })

        events
            .filter { event -> event.path in pytestConfigs }
            .forEach { event ->
                val file = event.file
                val services = pytestConfigs[event.path]

                services!!.forEach { service -> service.refreshPytestConfig(file) }
            }
    }
}


/**
 * Convert an fnmatch/wildcard (e.g. "test_*") pattern to a RegEx pattern string.
 *
 * NOTE: a handmade parser is used, in order to properly parse CamelCase word
 *       boundary dashes — a simple string replace would bungle legitimate dashes
 *       used within character classes ("[A-Z]")
 *
 * Heavy inspiration for this parser is derived from:
 *   https://stackoverflow.com/a/1248627/148585
 *
 * @param withDashes Whether to treat dashes as CamelCase word boundaries
 */
fun convertWildcardPatternToRegexPattern(wildcard: String, withDashes: Boolean): String {
    val chars = StringBuilder()
    var isEscaping = false
    var charClassLevel = 0

    var i = 0
    while (i < wildcard.length) {
        when (val char = wildcard[i]) {
            '*' -> chars.append(if (charClassLevel > 0) "*" else if (isEscaping) "\\*" else ".*")
            '?' -> chars.append(if (charClassLevel > 0) "?" else if (isEscaping) "\\?" else ".")
            '.', '(', ')', '^', '+', '|', '$' -> {
                if (charClassLevel == 0 || char == '^') {
                    chars.append("\\")
                }
                chars.append(char)
                isEscaping = false
            }
            '\\' -> {
                isEscaping =
                    if (isEscaping) {
                        chars.append("\\")
                        false
                    } else {
                        true
                    }
            }
            '[' -> {
                if (isEscaping) {
                    chars.append("\\[")
                } else {
                    chars.append('[')
                    charClassLevel++
                }
            }
            ']' -> {
                if (isEscaping) {
                    chars.append("\\]")
                } else {
                    chars.append(']')
                    charClassLevel--
                }
            }
            '!' -> {
                if (charClassLevel > 0 && wildcard[i - 1] == '[') {
                    chars.append('^')
                } else {
                    chars.append('!')
                }
            }
            '-' -> {
                if (withDashes && charClassLevel == 0) {
                    chars.append("[A-Z0-9]")
                } else {
                    chars.append('-')
                }
            }
            else -> {
                chars.append(char)
                isEscaping = false
            }
        }

        i++
    }

    /* Unclosed character classes would result in a PatternSyntaxException.
     * As a guard against this, we return a pattern matching nothing in cases
     * of unclosed character classes.
     */
    if (charClassLevel > 0) {
        // "Match nothing" pattern sourced from: https://stackoverflow.com/a/942122/148585
        return "(?!)"
    }

    return chars.toString()
}

/**
 * Convert a space-delimited list of fnmatch/wildcard patterns to a single Regex
 *
 * @param withDashes Whether to treat dashes as CamelCase word boundaries
 */
fun convertWildcardPatternsToRegex(patterns: String, withDashes: Boolean): Regex =
    patterns
        .split(Regex(" +"))
        .joinToString("|") {
            convertWildcardPatternToRegexPattern(it, withDashes)
        }
        .let { Regex(it) }


/**
 * Interface for providing access to a pytest config file (pytest.ini file or pyproject.toml section)
 */
abstract class PyTestConfig {
    abstract val pythonClassesRaw: String?
    abstract val pythonFunctionsRaw: String?

    val pythonClasses: Regex by lazy { convertWildcardPatternsToRegex(pythonClassesRaw ?: "Test*", true) }
    val pythonFunctions: Regex by lazy { convertWildcardPatternsToRegex(pythonFunctionsRaw ?: "test_*", false) }

    companion object {
        const val CONFIG_PYTHON_CLASSES = "python_classes"
        const val CONFIG_PYTHON_FUNCTIONS = "python_functions"

        fun parse(file: VirtualFile?): PyTestConfig? {
            if (file == null) return null

            return when (file.extension) {
                "ini" -> PyTestIni(file)
                "toml" -> PyTestPyProjectToml(file)
                else -> null
            }
        }
    }
}


/**
 * Parse a pytest.ini file and expose its contents
 */
class PyTestIni(pytestIniFile: VirtualFile): PyTestConfig() {
    private val pytestIni: Ini by lazy { Ini(pytestIniFile.inputStream) }

    override val pythonClassesRaw: String? by lazy { pytestIni.get(PYTEST_INI_SECTION, CONFIG_PYTHON_CLASSES) }
    override val pythonFunctionsRaw: String? by lazy { pytestIni.get(PYTEST_INI_SECTION, CONFIG_PYTHON_FUNCTIONS) }

    companion object {
        const val PYTEST_INI_SECTION = "pytest"
    }
}


/**
 * Parse a pyproject.toml file and expose its tool.pytest.ini_options section
 */
class PyTestPyProjectToml(pyprojectTomlFile: VirtualFile): PyTestConfig() {
    private val pyprojectToml: TomlParseResult by lazy { Toml.parse(pyprojectTomlFile.inputStream) }

    override val pythonClassesRaw: String? by lazy { pyprojectToml.getString("$PYPROJECT_PYTEST_SECTION.$CONFIG_PYTHON_CLASSES") }
    override val pythonFunctionsRaw: String? by lazy { pyprojectToml.getString("$PYPROJECT_PYTEST_SECTION.$CONFIG_PYTHON_FUNCTIONS") }

    companion object {
        const val PYPROJECT_PYTEST_SECTION = "tool.pytest.ini_options"
    }
}
