// Minimal Editor Cursor Example - Core Concepts
// Run with: scala-cli run SimpleCursorExample.scala

//> using scala "3"
//> using platform "native"

package cursorstructs.simple

import scala.scalanative.libc.stdio._
import scala.scalanative.unsafe._

// Simple cursor with start and end positions
object SimpleCursor {

  // Cursor struct: two positions (line, column) for start and end
  // CStruct4[Int, Int, Int, Int] = { startLine, startCol, endLine, endCol }
  type CursorStruct = CStruct4[Int, Int, Int, Int]

  // Initialize caller-owned cursor memory.
  def init(c: Ptr[CursorStruct], line: Int, col: Int): Ptr[CursorStruct] = {
    // Initialize both start and end to same position (collapsed cursor)
    c._1 = line // start line
    c._2 = col // start column
    c._3 = line // end line
    c._4 = col // end column
    c
  }

  // Getter/Setter helpers
  def startLine(c: Ptr[CursorStruct]): Int = c._1
  def startCol(c: Ptr[CursorStruct]): Int = c._2
  def endLine(c: Ptr[CursorStruct]): Int = c._3
  def endCol(c: Ptr[CursorStruct]): Int = c._4

  def setStart(c: Ptr[CursorStruct], line: Int, col: Int): Unit = {
    c._1 = line
    c._2 = col
  }

  def setEnd(c: Ptr[CursorStruct], line: Int, col: Int): Unit = {
    c._3 = line
    c._4 = col
  }

  // Check if cursor has selection (start != end)
  def hasSelection(c: Ptr[CursorStruct]): Boolean = {
    c._1 != c._3 || c._2 != c._4
  }

  // Move cursor (collapses selection)
  def moveTo(c: Ptr[CursorStruct], line: Int, col: Int): Unit = {
    c._1 = line
    c._2 = col
    c._3 = line
    c._4 = col
  }

  // Move right by n columns
  def moveRight(c: Ptr[CursorStruct], n: Int): Unit = {
    val newCol = c._4 + n
    moveTo(c, c._3, newCol)
  }

  // Select right by n columns (extends selection)
  def selectRight(c: Ptr[CursorStruct], n: Int): Unit = {
    c._4 = c._4 + n // Only move end position
  }

  // Select down by n lines
  def selectDown(c: Ptr[CursorStruct], n: Int): Unit = {
    c._3 = c._3 + n // Only move end line
  }

  // Collapse to end position
  def collapseToEnd(c: Ptr[CursorStruct]): Unit = {
    c._1 = c._3
    c._2 = c._4
  }

  // Print cursor state
  def print(c: Ptr[CursorStruct]): Unit = {
    if (hasSelection(c)) {
      printf(
        c"Cursor: (%d, %d) -> (%d, %d) [SELECTED]\n",
        c._1,
        c._2,
        c._3,
        c._4
      )
    } else {
      printf(c"Cursor: (%d, %d) [NO SELECTION]\n", c._1, c._2)
    }
  }
}

@main def simpleCursorExample(): Unit = {
  import SimpleCursor._

  printf(c"\n=== Simple Cursor Example ===\n\n")

  // Example 1: Create and move cursor
  printf(c"1. Creating cursor at (0, 0):\n")
  val cursor = init(stackalloc[CursorStruct](), 0, 0)
  print(cursor)

  printf(c"\n2. Moving right by 5:\n")
  moveRight(cursor, 5)
  print(cursor)

  printf(c"\n3. Selecting right by 10:\n")
  selectRight(cursor, 10)
  print(cursor)

  printf(c"\n4. Selecting down by 2 lines:\n")
  selectDown(cursor, 2)
  print(cursor)

  printf(c"\n5. Collapsing to end:\n")
  collapseToEnd(cursor)
  print(cursor)

  // Example 2: Multiple cursors on stack
  printf(c"\n\n=== Multiple Cursors ===\n")
  val c1 = init(stackalloc[CursorStruct](), 0, 0)
  val c2 = init(stackalloc[CursorStruct](), 1, 5)
  val c3 = init(stackalloc[CursorStruct](), 2, 10)

  printf(c"Cursor 1: "); print(c1)
  printf(c"Cursor 2: "); print(c2)
  printf(c"Cursor 3: "); print(c3)

  printf(c"\nAfter selecting right by 8 on each:\n")
  selectRight(c1, 8)
  selectRight(c2, 8)
  selectRight(c3, 8)

  printf(c"Cursor 1: "); print(c1)
  printf(c"Cursor 2: "); print(c2)
  printf(c"Cursor 3: "); print(c3)

  printf(c"\n=== Done! ===\n\n")
}
