// Editor Cursor Implementation using Scala Native Structs
// Demonstrates struct allocation, field access, and manipulation
// Run with: scala-cli run EditorCursorExample.scala

//> using scala "3"
//> using platform "native"

package cursorstructs.editor

import scala.scalanative.unsafe._

// Position represents a location in the editor (line, column)
object Position {
  type Ref = CStruct2[Int, Int]

  implicit class PositionOps(val ptr: Ptr[Ref]) extends AnyVal {
    def line: Int = ptr._1
    def line_=(value: Int): Unit = ptr._1 = value

    def column: Int = ptr._2
    def column_=(value: Int): Unit = ptr._2 = value

    def set(line: Int, column: Int): Unit = {
      ptr._1 = line
      ptr._2 = column
    }

    def copy(dest: Ptr[Ref]): Unit = {
      dest._1 = ptr._1
      dest._2 = ptr._2
    }
  }

  def create(line: Int, column: Int)(implicit zone: Zone): Ptr[Ref] = {
    val pos = alloc[Ref]()
    pos.set(line, column)
    pos
  }

  def init(pos: Ptr[Ref], line: Int, column: Int): Ptr[Ref] = {
    pos.set(line, column)
    pos
  }

  // Compare two positions: returns -1 if a < b, 0 if equal, 1 if a > b
  def compare(a: Ptr[Ref], b: Ptr[Ref]): Int = {
    if (a.line < b.line) -1
    else if (a.line > b.line) 1
    else if (a.column < b.column) -1
    else if (a.column > b.column) 1
    else 0
  }
}

// Cursor represents a selection range with start and end positions
object Cursor {
  import Position._

  // Cursor struct: { start: Position, end: Position }
  type Ref = CStruct2[Position.Ref, Position.Ref]

  implicit class CursorOps(val ptr: Ptr[Ref]) extends AnyVal {
    // Access start position
    def start: Ptr[Position.Ref] = ptr.at1

    // Access end position
    def end: Ptr[Position.Ref] = ptr.at2

    // Initialize cursor at a specific position (collapsed cursor)
    def initAt(line: Int, column: Int): Unit = {
      start.set(line, column)
      end.set(line, column)
    }

    // Check if cursor is collapsed (no selection)
    def isCollapsed: Boolean = {
      start.line == end.line && start.column == end.column
    }

    // Check if cursor has a selection
    def hasSelection: Boolean = !isCollapsed

    // Get the selection range in document order (start before end)
    def getRange: (Int, Int, Int, Int) = {
      if (Position.compare(start, end) <= 0) {
        (start.line, start.column, end.line, end.column)
      } else {
        (end.line, end.column, start.line, start.column)
      }
    }

    // Move cursor (collapses selection)
    def moveTo(line: Int, column: Int): Unit = {
      start.set(line, column)
      end.set(line, column)
    }

    // Move cursor by delta
    def moveBy(deltaLine: Int, deltaColumn: Int): Unit = {
      val newLine = end.line + deltaLine
      val newColumn = end.column + deltaColumn
      moveTo(newLine, newColumn)
    }

    // Move cursor right by n columns
    def moveRight(n: Int = 1): Unit = {
      moveTo(end.line, end.column + n)
    }

    // Move cursor left by n columns
    def moveLeft(n: Int = 1): Unit = {
      moveTo(end.line, end.column - n)
    }

    // Move cursor down by n lines
    def moveDown(n: Int = 1): Unit = {
      moveTo(end.line + n, end.column)
    }

    // Move cursor up by n lines
    def moveUp(n: Int = 1): Unit = {
      moveTo(end.line - n, end.column)
    }

    // Extend selection to a new position
    def selectTo(line: Int, column: Int): Unit = {
      end.set(line, column)
    }

    // Extend selection by delta (keeps start fixed)
    def selectBy(deltaLine: Int, deltaColumn: Int): Unit = {
      end.line = end.line + deltaLine
      end.column = end.column + deltaColumn
    }

    // Select right by n columns
    def selectRight(n: Int = 1): Unit = {
      end.column = end.column + n
    }

    // Select left by n columns
    def selectLeft(n: Int = 1): Unit = {
      end.column = end.column - n
    }

    // Select down by n lines
    def selectDown(n: Int = 1): Unit = {
      end.line = end.line + n
    }

    // Select up by n lines
    def selectUp(n: Int = 1): Unit = {
      end.line = end.line - n
    }

    // Select entire line
    def selectLine(line: Int, lineLength: Int): Unit = {
      start.set(line, 0)
      end.set(line, lineLength)
    }

    // Select from current position to end of line
    def selectToEndOfLine(lineLength: Int): Unit = {
      end.column = lineLength
    }

    // Select from start of line to current position
    def selectToStartOfLine(): Unit = {
      end.column = 0
    }

    // Collapse selection to start
    def collapseToStart(): Unit = {
      start.copy(end)
    }

    // Collapse selection to end
    def collapseToEnd(): Unit = {
      end.copy(start)
    }

    // Copy cursor state
    def copy(dest: Ptr[Ref]): Unit = {
      start.copy(dest.start)
      end.copy(dest.end)
    }
  }

  // Create a cursor in zone-scoped native memory.
  def create(line: Int, column: Int)(implicit zone: Zone): Ptr[Ref] = {
    val cursor = alloc[Ref]()
    cursor.initAt(line, column)
    cursor
  }

  // Initialize caller-owned cursor memory.
  def init(cursor: Ptr[Ref], line: Int, column: Int): Ptr[Ref] = {
    cursor.initAt(line, column)
    cursor
  }
}

// Editor buffer for demonstration
class EditorBuffer {
  import Cursor._
  import Position._

  private var lines =
    Array("Hello, World!", "Scala Native is awesome", "Structs are fast")

  def lineCount: Int = lines.length

  def getLine(index: Int): String = {
    if (index >= 0 && index < lines.length) lines(index)
    else ""
  }

  def lineLength(index: Int): Int = {
    if (index >= 0 && index < lines.length) lines(index).length
    else 0
  }

  def getSelectedText(cursor: Ptr[Cursor.Ref]): String = {
    if (cursor.isCollapsed) return ""

    val (startLine, startCol, endLine, endCol) = cursor.getRange

    if (startLine == endLine) {
      // Single line selection
      val line = getLine(startLine)
      line.substring(startCol, Math.min(endCol, line.length))
    } else {
      // Multi-line selection
      val builder = new StringBuilder

      // First line
      builder.append(getLine(startLine).substring(startCol))
      builder.append("\n")

      // Middle lines
      for (i <- (startLine + 1).until(endLine)) {
        builder.append(getLine(i))
        builder.append("\n")
      }

      // Last line
      val lastLine = getLine(endLine)
      builder.append(lastLine.substring(0, Math.min(endCol, lastLine.length)))

      builder.toString
    }
  }

  def clampCursor(cursor: Ptr[Cursor.Ref]): Unit = {
    // Clamp start position
    if (cursor.start.line < 0) cursor.start.line = 0
    if (cursor.start.line >= lineCount) cursor.start.line = lineCount - 1
    if (cursor.start.column < 0) cursor.start.column = 0

    val startLineLen = lineLength(cursor.start.line)
    if (cursor.start.column > startLineLen) cursor.start.column = startLineLen

    // Clamp end position
    if (cursor.end.line < 0) cursor.end.line = 0
    if (cursor.end.line >= lineCount) cursor.end.line = lineCount - 1
    if (cursor.end.column < 0) cursor.end.column = 0

    val endLineLen = lineLength(cursor.end.line)
    if (cursor.end.column > endLineLen) cursor.end.column = endLineLen
  }
}

// Example usage demonstrating all features
object EditorCursorExample {
  import Cursor._
  import Position._

  def printSeparator(): Unit = println("=" * 60)

  def show(cursor: Ptr[Cursor.Ref]): String = {
    if (cursor.isCollapsed) {
      s"Cursor(${cursor.start.line}:${cursor.start.column})"
    } else {
      s"Cursor(${cursor.start.line}:${cursor.start.column} -> ${cursor.end.line}:${cursor.end.column})"
    }
  }

  // Example 1: Basic cursor creation and movement
  def example1_BasicMovement(): Unit = {
    println("\n=== Example 1: Basic Cursor Movement ===")

    val cursor = Cursor.init(stackalloc[Cursor.Ref](), 0, 0)
    println(s"Initial: ${show(cursor)}")

    cursor.moveRight(5)
    println(s"After moveRight(5): ${show(cursor)}")

    cursor.moveDown(1)
    println(s"After moveDown(1): ${show(cursor)}")

    cursor.moveTo(2, 10)
    println(s"After moveTo(2, 10): ${show(cursor)}")
  }

  // Example 2: Selection operations
  def example2_Selection(): Unit = {
    println("\n=== Example 2: Selection Operations ===")

    val cursor = Cursor.init(stackalloc[Cursor.Ref](), 0, 0)
    println(s"Initial: ${show(cursor)} (collapsed: ${cursor.isCollapsed})")

    cursor.selectRight(5)
    println(
      s"After selectRight(5): ${show(cursor)} (has selection: ${cursor.hasSelection})"
    )

    cursor.selectDown(1)
    println(s"After selectDown(1): ${show(cursor)}")

    val (sl, sc, el, ec) = cursor.getRange
    println(s"Range: ($sl, $sc) -> ($el, $ec)")

    cursor.collapseToEnd()
    println(
      s"After collapseToEnd(): ${show(cursor)} (collapsed: ${cursor.isCollapsed})"
    )
  }

  // Example 3: Working with editor buffer
  def example3_EditorIntegration(): Unit = {
    println("\n=== Example 3: Editor Integration ===")

    val editor = new EditorBuffer
    val cursor = Cursor.init(stackalloc[Cursor.Ref](), 0, 0)

    println(s"Buffer has ${editor.lineCount} lines")
    println(s"Line 0: '${editor.getLine(0)}'")
    println(s"Line 1: '${editor.getLine(1)}'")
    println()

    // Select "Hello" from first line
    cursor.moveTo(0, 0)
    cursor.selectRight(5)
    println(s"Cursor: ${show(cursor)}")
    println(s"Selected text: '${editor.getSelectedText(cursor)}'")
    println()

    // Select entire first line
    cursor.selectLine(0, editor.lineLength(0))
    println(s"Cursor: ${show(cursor)}")
    println(s"Selected text: '${editor.getSelectedText(cursor)}'")
    println()

    // Multi-line selection
    cursor.moveTo(0, 7)
    cursor.selectTo(1, 13)
    println(s"Cursor: ${show(cursor)}")
    println(s"Selected text:\n'${editor.getSelectedText(cursor)}'")
  }

  // Example 4: Multiple cursors (using stack allocation)
  def example4_MultipleCursors(): Unit = {
    println("\n=== Example 4: Multiple Cursors (Stack Allocated) ===")

    // Allocate 3 cursors on the stack
    val cursor1 = stackalloc[Cursor.Ref]()
    val cursor2 = stackalloc[Cursor.Ref]()
    val cursor3 = stackalloc[Cursor.Ref]()

    cursor1.initAt(0, 0)
    cursor2.initAt(1, 5)
    cursor3.initAt(2, 10)

    println(s"Cursor 1: ${show(cursor1)}")
    println(s"Cursor 2: ${show(cursor2)}")
    println(s"Cursor 3: ${show(cursor3)}")

    // Move all cursors right
    cursor1.moveRight(3)
    cursor2.moveRight(3)
    cursor3.moveRight(3)

    println("\nAfter moving right by 3:")
    println(s"Cursor 1: ${show(cursor1)}")
    println(s"Cursor 2: ${show(cursor2)}")
    println(s"Cursor 3: ${show(cursor3)}")
  }

  // Example 5: Cursor state copying
  def example5_CursorCopy(): Unit = {
    println("\n=== Example 5: Cursor State Copying ===")

    val cursor1 = Cursor.init(stackalloc[Cursor.Ref](), 1, 5)
    cursor1.selectRight(10)

    val cursor2 = Cursor.init(stackalloc[Cursor.Ref](), 0, 0)

    println(s"Before copy:")
    println(s"  Cursor 1: ${show(cursor1)}")
    println(s"  Cursor 2: ${show(cursor2)}")

    // Copy cursor1 state to cursor2
    cursor1.copy(cursor2)

    println(s"\nAfter copying cursor1 to cursor2:")
    println(s"  Cursor 1: ${show(cursor1)}")
    println(s"  Cursor 2: ${show(cursor2)}")

    // Modify cursor2 - cursor1 remains unchanged
    cursor2.moveDown(2)

    println(s"\nAfter modifying cursor2:")
    println(s"  Cursor 1: ${show(cursor1)}")
    println(s"  Cursor 2: ${show(cursor2)}")
  }

  // Example 6: Advanced selection patterns
  def example6_AdvancedSelection(): Unit = {
    println("\n=== Example 6: Advanced Selection Patterns ===")

    val editor = new EditorBuffer
    val cursor = Cursor.init(stackalloc[Cursor.Ref](), 1, 0)

    // Select word-by-word simulation
    println("Selecting 'Scala Native' from line 1:")
    cursor.moveTo(1, 0)
    cursor.selectRight(12) // "Scala Native"
    println(s"  Cursor: ${show(cursor)}")
    println(s"  Text: '${editor.getSelectedText(cursor)}'")

    // Extend selection
    println("\nExtending selection to include ' is awesome':")
    cursor.selectRight(11)
    println(s"  Cursor: ${show(cursor)}")
    println(s"  Text: '${editor.getSelectedText(cursor)}'")

    // Select with clamping
    println("\nMoving cursor beyond bounds (will be clamped):")
    cursor.moveTo(0, 0)
    cursor.selectRight(9999)
    editor.clampCursor(cursor)
    println(s"  Cursor (clamped): ${show(cursor)}")
    println(s"  Text: '${editor.getSelectedText(cursor)}'")
  }

  def main(args: Array[String]): Unit = {
    printSeparator()
    println("EDITOR CURSOR IMPLEMENTATION - SCALA NATIVE STRUCT EXAMPLES")
    printSeparator()

    example1_BasicMovement()
    example2_Selection()
    example3_EditorIntegration()
    example4_MultipleCursors()
    example5_CursorCopy()
    example6_AdvancedSelection()

    printSeparator()
    println("\nAll examples completed successfully!")
    printSeparator()
  }
}
