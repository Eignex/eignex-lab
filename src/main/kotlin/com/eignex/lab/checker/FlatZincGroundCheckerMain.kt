package com.eignex.lab.checker

import java.nio.file.Path
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.BailErrorStrategy
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.chocosolver.parser.Level
import org.chocosolver.parser.flatzinc.Flatzinc4Lexer
import org.chocosolver.parser.flatzinc.Flatzinc4Parser
import org.chocosolver.parser.flatzinc.ast.Datas
import org.chocosolver.solver.Model
import org.chocosolver.solver.SettingsBuilder
import org.chocosolver.solver.variables.IntVar
import org.chocosolver.util.ESat

/** Parse an already fixed FlatZinc frame and call Choco's existing solution-checking API without search or propagation. */
object FlatZincGroundCheckerMain {
    const val MARKER = "LAB-FZN-CHECK"

    @JvmStatic
    fun main(args: Array<String>) {
        val state = args.singleOrNull()?.let { evaluate(Path.of(it)) } ?: "UNKNOWN"
        println("$MARKER\t$state")
    }

    internal fun evaluate(path: Path): String =
        try {
            val model = Model("fixed-assignment", SettingsBuilder.init().setLCG(false).setEnableSAT(false))
            val errors = object : BaseErrorListener() {
                override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int,
                                         charPositionInLine: Int, msg: String?, error: RecognitionException?) {
                    throw ParseCancellationException("FlatZinc syntax error at $line:$charPositionInLine: $msg")
                }
            }
            val frame = FixedDiffn.bind(java.nio.file.Files.readString(path), model)
            val lexer = Flatzinc4Lexer(CharStreams.fromString(frame)).apply {
                removeErrorListeners()
                addErrorListener(errors)
            }
            val parser = Flatzinc4Parser(CommonTokenStream(lexer)).apply {
                removeErrorListeners()
                addErrorListener(errors)
                errorHandler = BailErrorStrategy()
            }
            parser.flatzinc_model(model, Datas(model, Level.SILENT, false))
            check(parser.numberOfSyntaxErrors == 0 && parser.currentToken.type == Token.EOF)
            if (model.vars.any { it !is IntVar || !it.isInstantiated }) "UNKNOWN" else {
                val checks = model.cstrs.map { it to it.isSatisfied() }
                when {
                    checks.any { it.second == ESat.FALSE } -> {
                        System.err.println(checks.first { it.second == ESat.FALSE }.first.toString().take(2000))
                        "INVALID"
                    }
                    checks.all { it.second == ESat.TRUE } -> "VALID"
                    else -> "UNKNOWN"
                }
            }
        } catch (failure: Throwable) {
            System.err.println("${failure::class.simpleName}: ${failure.message}".take(2000))
            "UNKNOWN"
        }
}
