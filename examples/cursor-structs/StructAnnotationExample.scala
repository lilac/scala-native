// @struct Annotation Example - Using Scala Classes as Structs
// This demonstrates the high-level @struct annotation approach
// Run with: scala-cli run StructAnnotationExample.scala

//> using scala "3"
//> using platform "native"

package cursorstructs.annotation

import scala.scalanative.libc.stdio._
import scala.scalanative.runtime.struct
import scala.scalanative.unsafe._

// ============================================================================
// Using @struct annotation - Immutable pass-by-value structures
// ============================================================================

/** Position in the editor - marked as a struct for pass-by-value semantics
 *
 *  @struct
 *    classes are:
 *    - Immutable (all fields must be val)
 *    - Passed by value (copied, not referenced)
 *    - Optimized by the compiler
 *    - Cannot be used directly - must be used via Ptr when interacting with C
 */
@struct class Position(val line: Int, val column: Int)

/** Cursor with start and end positions
 *
 *  Note: @struct classes can contain other @struct classes
 */
@struct class Cursor(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int
)

// Alternative: Nested struct approach (currently limited support)
// @struct class CursorWithNested(val start: Position, val end: Position)

object StructAnnotationExample {

  // Helper to initialize caller-owned cursor memory.
  def initCursor(c: Ptr[Cursor], line: Int, col: Int): Ptr[Cursor] = {
    // Note: With @struct annotation, we still access via Ptr
    // The fields are at specific offsets in memory
    setCursorStart(c, line, col)
    c
  }

  // Access cursor fields via pointer arithmetic
  def getCursorStart(c: Ptr[Cursor]): (Int, Int) = {
    // @struct fields are laid out in memory in declaration order
    val startLinePtr = c.asInstanceOf[Ptr[Int]]
    val startColPtr = (c.asInstanceOf[Ptr[Byte]] + 4).asInstanceOf[Ptr[Int]]
    (!startLinePtr, !startColPtr)
  }

  def setCursorStart(c: Ptr[Cursor], line: Int, col: Int): Unit = {
    val startLinePtr = c.asInstanceOf[Ptr[Int]]
    val startColPtr = (c.asInstanceOf[Ptr[Byte]] + 4).asInstanceOf[Ptr[Int]]
    !startLinePtr = line
    !startColPtr = col
  }

  def printCursor(c: Ptr[Cursor]): Unit = {
    val (sl, sc) = getCursorStart(c)
    printf(c"Cursor at (%d, %d)\n", sl, sc)
  }
}

// ============================================================================
// Comparison: @struct vs CStruct
// ============================================================================

object ComparisonExample {

  // Approach 1: @struct annotation (higher level, immutable)
  @struct class StructCursor(
      val startLine: Int,
      val startColumn: Int,
      val endLine: Int,
      val endColumn: Int
  )

  // Approach 2: CStruct (lower level, direct memory access)
  type CStructCursor = CStruct4[Int, Int, Int, Int]

  implicit class CStructCursorOps(val ptr: Ptr[CStructCursor]) extends AnyVal {
    def startLine: Int = ptr._1
    def startLine_=(v: Int): Unit = ptr._1 = v
    def startColumn: Int = ptr._2
    def startColumn_=(v: Int): Unit = ptr._2 = v
    def endLine: Int = ptr._3
    def endLine_=(v: Int): Unit = ptr._3 = v
    def endColumn: Int = ptr._4
    def endColumn_=(v: Int): Unit = ptr._4 = v
  }

  def demonstrateComparison(): Unit = {
    printf(c"\n=== @struct vs CStruct Comparison ===\n\n")

    // Using CStruct (recommended for most use cases)
    printf(c"Using CStruct (direct field access):\n")
    val cstruct = stackalloc[CStructCursor]()
    cstruct.startLine = 10
    cstruct.startColumn = 20
    printf(c"  Position: (%d, %d)\n", cstruct.startLine, cstruct.startColumn)

    // Using @struct (requires more manual work)
    printf(c"\nUsing @struct (requires manual offset calculation):\n")
    val sstruct = stackalloc[StructCursor]()
    // Access requires pointer arithmetic
    val linePtr = sstruct.asInstanceOf[Ptr[Int]]
    val colPtr = (sstruct.asInstanceOf[Ptr[Byte]] + 4).asInstanceOf[Ptr[Int]]
    !linePtr = 10
    !colPtr = 20
    printf(c"  Position: (%d, %d)\n", !linePtr, !colPtr)
  }
}

// ============================================================================
// Real-world example: LLVM intrinsics use @struct
// ============================================================================

object LLVMIntrinsicsStyleExample {

  // This is how Scala Native uses @struct internally for LLVM intrinsics
  @struct class IntOverflow(val value: Int, val flag: Boolean)

  // Simulated overflow check function
  def addWithOverflow(
      result: Ptr[IntOverflow],
      a: Int,
      b: Int
  ): Ptr[IntOverflow] = {
    val valuePtr = result.asInstanceOf[Ptr[Int]]
    val flagPtr =
      (result.asInstanceOf[Ptr[Byte]] + 4).asInstanceOf[Ptr[Boolean]]

    val sum = a.toLong + b.toLong
    val didOverflow = sum > Int.MaxValue || sum < Int.MinValue

    !valuePtr = if (didOverflow) 0 else sum.toInt
    !flagPtr = didOverflow

    result
  }

  def demonstrateLLVMStyle(): Unit = {
    printf(c"\n=== LLVM Intrinsics Style (using @struct) ===\n\n")

    val result1 = addWithOverflow(stackalloc[IntOverflow](), 100, 200)
    val val1 = !(result1.asInstanceOf[Ptr[Int]])
    val flag1 =
      !((result1.asInstanceOf[Ptr[Byte]] + 4).asInstanceOf[Ptr[Boolean]])
    val overflow1: Int = if (flag1) 1 else 0
    printf(c"100 + 200 = %d, overflow: %d\n", val1, overflow1)

    val result2 = addWithOverflow(stackalloc[IntOverflow](), Int.MaxValue, 1)
    val val2 = !(result2.asInstanceOf[Ptr[Int]])
    val flag2 =
      !((result2.asInstanceOf[Ptr[Byte]] + 4).asInstanceOf[Ptr[Boolean]])
    val overflow2: Int = if (flag2) 1 else 0
    printf(c"MaxInt + 1 = %d, overflow: %d\n", val2, overflow2)
  }
}

// ============================================================================
// RECOMMENDED: Use CStruct for your cursor
// ============================================================================

object RecommendedCursorImplementation {

  type Cursor = CStruct4[Int, Int, Int, Int]

  implicit class CursorOps(val ptr: Ptr[Cursor]) extends AnyVal {
    def startLine: Int = ptr._1
    def startLine_=(v: Int): Unit = ptr._1 = v
    def startColumn: Int = ptr._2
    def startColumn_=(v: Int): Unit = ptr._2 = v
    def endLine: Int = ptr._3
    def endLine_=(v: Int): Unit = ptr._3 = v
    def endColumn: Int = ptr._4
    def endColumn_=(v: Int): Unit = ptr._4 = v

    def moveTo(line: Int, col: Int): Unit = {
      startLine = line
      startColumn = col
      endLine = line
      endColumn = col
    }

    def selectRight(n: Int): Unit = {
      endColumn = endColumn + n
    }

    def hasSelection: Boolean = {
      startLine != endLine || startColumn != endColumn
    }
  }

  def demonstrate(): Unit = {
    printf(c"\n=== RECOMMENDED: CStruct Implementation ===\n\n")

    val cursor = stackalloc[Cursor]()
    cursor.moveTo(5, 10)
    printf(c"Cursor at (%d, %d)\n", cursor.startLine, cursor.startColumn)

    cursor.selectRight(15)
    printf(
      c"After select: (%d, %d) -> (%d, %d)\n",
      cursor.startLine,
      cursor.startColumn,
      cursor.endLine,
      cursor.endColumn
    )

    val hasSelection: Int = if (cursor.hasSelection) 1 else 0
    printf(c"Has selection: %d\n", hasSelection)
  }
}

// ============================================================================
// Main
// ============================================================================

@main def structAnnotationDemo(): Unit = {
  printf(c"=== @struct Annotation in Scala Native ===\n")

  ComparisonExample.demonstrateComparison()
  LLVMIntrinsicsStyleExample.demonstrateLLVMStyle()
  RecommendedCursorImplementation.demonstrate()

  printf(c"\n=== KEY TAKEAWAYS ===\n")
  printf(c"1. @struct exists but requires manual pointer arithmetic\n")
  printf(c"2. CStruct provides better ergonomics with _1, _2 access\n")
  printf(c"3. For cursors, use CStruct4[Int,Int,Int,Int]\n")
  printf(c"4. @struct is mainly for internal/LLVM interop use\n\n")
}
