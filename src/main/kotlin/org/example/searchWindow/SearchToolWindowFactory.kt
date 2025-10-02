package org.example.searchWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

class SearchToolWindowFactory : ToolWindowFactory {
    var searchJob: Job? = null
    val scope = CoroutineScope(Dispatchers.IO)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val searchPanel = JPanel()
        val dirField = JTextField(25)
        searchPanel.add(JLabel("Directory path:"))
        searchPanel.add(dirField)
        val stringField = JTextField(25)
        searchPanel.add(JLabel("String for search:"))
        searchPanel.add(stringField)
        val searchButton = JButton("Start search")
        searchPanel.add(searchButton)
        val cancelButton = JButton("Cancel search")
        searchPanel.add(cancelButton)

        val resultsArea = JTextArea()
        resultsArea.isEditable = false
        val resultsPanel = JBScrollPane(resultsArea)

        val panel = JPanel(BorderLayout())
        panel.add(searchPanel, BorderLayout.NORTH)
        panel.add(resultsPanel, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        Disposer.register(toolWindow.disposable) {
            scope.cancel()
        }

        searchButton.addActionListener {
            searchJob?.cancel()
            SwingUtilities.invokeLater {
                resultsArea.text = ""
            }
            val stringToSearch = stringField.text
            val directory = Path.of(dirField.text)
            println("Current working directory: ${System.getProperty("user.dir")}")

            if (stringToSearch.isBlank()) {
                SwingUtilities.invokeLater {
                    resultsArea.text = "String to search cannot be empty"
                }
                return@addActionListener
            }
            if (!Files.exists(directory) || !Files.isDirectory(directory)) {
                SwingUtilities.invokeLater {
                    resultsArea.text = "Directory does not exist or is not a directory"
                }
                return@addActionListener
            }

            searchJob = scope.launch(Dispatchers.IO) {
                try {
                    searchForTextOccurrences(stringToSearch, directory)
                        .collect { occ ->
                            SwingUtilities.invokeLater {
                                resultsArea.append("${occ.file}:${occ.line}:${occ.offset}\n")
                            }
                        }
                } catch (e: CancellationException) {
                    SwingUtilities.invokeLater {
                        resultsArea.append("Search cancelled")
                    }
                    throw e
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        resultsArea.append("Error: ${e.message}\n")
                    }
                } finally {
                    searchJob = null
                }
            }
        }

        cancelButton.addActionListener {
            searchJob?.cancel()
            searchJob = null
            SwingUtilities.invokeLater {
                resultsArea.text = ""
                resultsArea.append("Search cancelled")
            }
        }
    }

    interface Occurrence {
        val file: Path
        val line: Int
        val offset: Int
    }

    fun searchForTextOccurrences(
        stringToSearch: String,
        directory: Path
    ): Flow<Occurrence> = channelFlow {
        val files = Files.walk(directory)
            .filter { Files.isRegularFile(it) }
            .toList()
        for (file in files) {
            launch(Dispatchers.IO) {
                try {
                    val content = Files.readString(file)
                    var currLine = 1
                    var offset = 0
                    var lastIndex = 0
                    var index = content.indexOf(stringToSearch, 0)
                    while (index >= 0) {
                        for (i in lastIndex until index) {
                            if (content[i] == '\n') {
                                currLine++
                                offset = 0
                            } else {
                                offset++
                            }
                        }
                        lastIndex = index
                        index = content.indexOf(stringToSearch, index + 1)
                        val occurrence = object : Occurrence {
                            override val file: Path = file
                            override val line: Int = currLine
                            override val offset: Int = offset
                        }
                        send(occurrence)
                    }
                } catch (e: Exception) {
                    println("Error reading file $file: ${e.message}")
                }
            }
        }
    }
}
