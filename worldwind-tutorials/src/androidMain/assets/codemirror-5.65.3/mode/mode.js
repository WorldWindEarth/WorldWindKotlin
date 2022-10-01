var verbose = 0

function Context(name, tokenType, depth, parent, line, pos) {
  this.name = name
  this.tokenType = tokenType
  this.depth = depth
  this.parent = parent
  this.startLine = line
  this.startPos = pos
}

var MAX_LOOKAHEAD_LINES = 3

function MatchContext() {
  this.stream = null
  this.line = this.startPos = 0
  this.string = this.startLine = ""
  this.copyInstance = null
}

MatchContext.prototype.start = function(stream) {
  this.stream = stream
  this.line = 0
  this.string = stream.string.slice(stream.start)
  this.startLine = stream.string
  this.startPos = stream.start
  return this
}

MatchContext.prototype.startLinebreak = function() {
  this.stream = null
  this.line = this.startPos = 0
  this.string = "\n"
  this.startLine = ""
  return this
}

MatchContext.prototype.copy = function() {
  var copy = this.copyInstance || (this.copyInstance = new MatchContext)
  copy.stream = this.stream
  copy.startPos = this.startPos
  copy.line = this.line
  copy.startLine = this.startLine
  copy.string = this.string
  return copy
}

MatchContext.prototype.updateStart = function() {
  this.startLine = !this.stream ? "" : this.line == 0 ? this.stream.string : this.stream.lookAhead(this.line)
  this.startPos = this.startLine.length - (this.string.length - 1)
}

MatchContext.prototype.ahead = function(n) {
  for (;;) {
    if (n <= this.string.length) return true
    if (this.string.charCodeAt(this.string.length - 1) !== 10) {
      this.string += "\n"
    } else if (this.line === MAX_LOOKAHEAD_LINES || !this.stream || !this.stream.lookAhead) {
      return false
    } else {
      var next = this.stream.lookAhead(this.line + 1)
      if (next == null) return false
      this.string += next + "\n"
      this.line++
    }
  }
}

var tokenValue = null

var stateClass = function(graph, options) {
  function StateClass(stack, context) {
    this.stack = stack
    this.context = context
  }

  StateClass.prototype.matchNext = function(mcx, pos, maxSkip, top) {
    var depth = this.stack.length - 1, node = this.stack[depth], edges = graph.nodes[node]

    for (var i = 0; i < edges.length; i++) {
      var op = edges[i], matched, to // See compileEdge in compile.js
      if (op === 0) { // Null match
        matched = pos
        to = edges[++i]
      } else if (op === 1 || op === 2) {   // 1, callTarget, returnTo
        var target = edges[++i]            // 2, callTarget, returnTo, context
        var returnTo = edges[++i]
        this.go(returnTo)
        var oldContext = this.context
        if (op === 2) {
          var cx = edges[++i]
          this.context = new Context(cx.name, cx.token, this.stack.length, this.context, mcx.startLine, mcx.startPos)
        }
        this.stack.push(target)
        var inner = this.matchNext(mcx, pos, 0, false)
        if (inner === pos) inner = this.matchNext(mcx, pos, i == edges.length - 1 ? maxSkip : 0, top)
        if (inner < 0) { // Reset state when the call fails
          this.stack.length = depth + 1
          this.stack[depth] = node
          this.context = oldContext
          continue
        }
        return inner
      } else if (op === 3) { // 3, tokenType, matchExpr, nextNode
        var token = edges[++i]
        matched = this.matchExpr(edges[++i], mcx, pos)
        to = edges[++i]
        if (matched > pos) tokenValue = token
      } else { // matchExpr, nextNode
        matched = this.matchExpr(op, mcx, pos)
        to = edges[++i]
      }

      if (matched < 0) {
        if (maxSkip > 0 && i == edges.length - 1) {
          if (verbose > 0) console["log"]("Dead end at", mcx.string.slice(pos), node, this.stack.join())
          maxSkip--
          matched = pos
        } else {
          continue
        }
      }
      this.go(to)
      if (!top && to === -1 || this.stack.length === 0) return matched

      if (matched > pos) {
        if (verbose > 1)
          console["log"]("Token", JSON.stringify(mcx.string.slice(pos, matched)), "from", node, "to", to, "under", this.stack.join())
        return matched
      } else {
        matched = this.matchNext(mcx, pos, i == edges.length - 1 ? maxSkip : 0, top)
        if (matched >= 0) return matched
        this.stack.length = depth + 1
        this.stack[depth] = node
      }
    }
    return -1
  }

  StateClass.prototype.go = function(to) {
    this.stack.pop()
    while (this.context && this.context.depth > this.stack.length)
      this.context = this.context.parent
    if (to !== -1) this.stack.push(to)
  }

  StateClass.prototype.runMaybe = function(mcx, pos, maxSkip) {
    tokenValue = null
    return this.matchNext(mcx, pos, maxSkip, true)
  }

  StateClass.prototype.forward = function(mcx, pos) {
    var progress = this.runMaybe(mcx, pos, 2)
    if (progress < 0) {
      if (verbose > 0) console["log"]("Lost it at", mcx.string.slice(pos), this.stack.join())
      this.stack.push(graph.token)
      progress = this.runMaybe(mcx, pos, 0)
    }
    return progress
  }

  StateClass.prototype.lookahead = function(mcx, pos, start) {
    var oldTokenValue = tokenValue
    var state = new this.constructor([start], null)
    mcx = mcx.copy()
    for (;;) {
      mcx.updateStart()
      // FIXME implement custom scanning algorithm. This one breaks when a sub-match fails
      var newPos = state.runMaybe(mcx, pos, 0)
      if (newPos < 0) { tokenValue = oldTokenValue; return false }
      if (state.stack.length === 0) { tokenValue = oldTokenValue; return true }
      pos = newPos
    }
  }

  StateClass.prototype.matchExpr = function(expr, mcx, pos) {
    if (typeof expr === "string") {
      var end = pos + expr.length
      return mcx.ahead(end) && mcx.string.slice(pos, end) === expr ? end : -1
    }
    if (expr.exec) {
      var m = mcx.ahead(pos + 1) && expr.exec(pos > 0 ? mcx.string.slice(pos) : mcx.string)
      if (!m) return -1
      return pos + m[0].length
    }

    var op = expr[0]
    if (op === 0) { // OP_SEQ, ...rest
      for (var i = 1; i < expr.length; i++) {
        pos = this.matchExpr(expr[i], mcx, pos)
        if (pos < 0) return -1
      }
      return pos
    } else if (op === 1) { // OP_CHOICE, ...rest
      for (var i = 1, e = expr.length - 1;; i++) {
        var cur = this.matchExpr(expr[i], mcx, pos)
        if (i === e || cur > -1) return cur
      }
      return -1
    } else if (op === 2 || op === 3) { // OP_STAR/OP_PLUS, expr
      if (op === 3 && (pos = this.matchExpr(expr[1], mcx, pos)) < 0) return -1
      for (;;) {
        var inner = this.matchExpr(expr[1], mcx, pos)
        if (inner == -1) return pos
        pos = inner
      }
    } else if (op === 4) { // OP_MAYBE, expr
      return Math.max(this.matchExpr(expr[1], mcx, pos), pos)
    } else if (op === 5) { // OP_LOOKAHEAD, expr
      return this.lookahead(mcx, pos, expr[1]) ? pos : -1
    } else if (op === 6) { // OP_NEG_LOOKAHEAD, expr
      return this.lookahead(mcx, pos, expr[1]) ? -1 : pos
    } else if (op === 7) { // OP_PREDICATE, name
      var lineStart = pos ? mcx.string.lastIndexOf("\n", pos - 1) : -1, line, linePos
      if (mcx.stream && lineStart < 0) {
        line = mcx.stream.string
        linePos = pos + mcx.stream.start
      } else {
        var lineEnd = mcx.string.indexOf("\n", pos)
        line = mcx.string.slice(lineStart + 1, lineEnd < 0 ? mcx.string.length : lineEnd)
        linePos = pos - (lineStart + 1)
      }
      return options.predicates[expr[1]](line, linePos, this.context, mcx.stream ? nextLines(mcx.stream) : noNextLines) ? pos : -1
    } else {
      throw new Error("Unknown match type " + expr)
    }
  }

  function noNextLines() { return null }

  function nextLines(stream) { return function(n) { return stream.lookAhead(n) } }

  StateClass.prototype.contextAt = function(line, linePos) {
    var copy = this.copy(), mcx = new MatchContext, pos = 0, lastCx = this.context
    mcx.string = line + "\n"
    mcx.startLine = line
    for (;;) {
      var matched = copy.runMaybe(mcx, pos, 0)
      if (matched == -1) return copy.context
      if (matched > linePos) {
        var context = copy.context
        if (pos == linePos) {
          trim: while (context) {
            for (var prev = lastCx; prev; prev = prev.parent) if (prev === context) break trim
            context = context.parent
          }
        }
        return context
      }
      pos = matched
      lastCx = copy.context
    }
  }

  StateClass.prototype.copy = function() {
    return new this.constructor(this.stack.slice(), this.context)
  }

  StateClass.start = function() {
    return new this([graph.start], null)
  }

  return StateClass
}

// declare global: CodeMirror
function GrammarMode(graph, options) {
  this.State = stateClass(graph, options || {})
  this.mcx = new MatchContext
}
CodeMirror.GrammarMode = GrammarMode

GrammarMode.prototype.startState = function() { return this.State.start() }

GrammarMode.prototype.copyState = function(state) { return state.copy() }

GrammarMode.prototype.token = function(stream, state) {
  stream.pos += state.forward(this.mcx.start(stream), 0)
  var tokenType = tokenValue
  for (var cx = state.context; cx; cx = cx.parent)
    if (cx.tokenType) tokenType = cx.tokenType + (tokenType ? " " + tokenType : "")
  if (stream.eol())
    state.forward(this.mcx, stream.pos - stream.start)
  return tokenType
}

GrammarMode.prototype.blankLine = function(state) {
  state.forward(this.mcx.startLinebreak(), 0)
}