# Multiline and raw string literals : JEP 378 dedenting, escapes, raw strings, and lexical errors

References : BIP026-D1..D3, BIP026-I1, StringLiterals, BabbleScriptEngine

```babble
;; babble preamble
def r = 7
def Box = record (r: int) (
    def sum = fn (x: int): int -> (this.r + x)
)
```

## Standard escapes and raw single-line strings

```babble
assert("a\b\t\n\f\r\"\'\\\0\u0041" == "a\u0008\t\n\u000c\r\"'\\\u0000A")
assert(r"" == "")
assert(r"\n\t\s\u0041" == "\\n\\t\\s\\u0041")
assert(r"C:\dir\" == "C:\\dir\\")
```

## r remains an ordinary identifier alongside raw strings

```babble
assert(r == 7)
assert(Box(3).r == 3)
assert(Box(3).sum(r) == 10)
assert(r"ok" == "ok")
```

## Basic JEP 378 dedent anchored on closing delimiter vs last content line

```babble
def a = """
      x
    y
    """
def b = """
    x
    y"""
assert(a == "  x\ny\n")
assert(b == "x\ny")
```

## Empty multiline blocks and whitespace-only opening line

```babble
def a = """   
    """
def b = """
"""
def c = r"""	
"""
assert(a == "")
assert(b == "")
assert(c == "")
```

## Blank lines (even shorter than minIndent) become empty lines

```babble
def s = """
    top
  
    bottom
    """
assert(s == "top\n\nbottom\n")
```

## Uniform trailing horizontal whitespace stripping and \s fencing

```babble
def esc = """
    ab   
    cd\s   
    """
def raw = r"""
    ef   	
    gh\s   
    """
assert(esc == "ab\ncd \n")
assert(raw == "ef\ngh\\s\n")
```

## Line continuation \<newline>, trailing spaces after \, escaped backslash before newline, and \uXXXX

```babble
def s = """
    alpha \
    beta \   
    gamma\\
    \u0020\u0042
    """
assert(s == "alpha beta gamma\\\n B\n")
```

## Embedded quotes in escaped and raw multiline blocks

```babble
def esc = """
    "one" ""two"" ""\" \"\"\"
    """
def raw = r"""
    "one" ""two""
    """
assert(esc == "\"one\" \"\"two\"\" \"\"\" \"\"\"\n")
assert(raw == "\"one\" \"\"two\"\"\n")
```

## Uniform tab-only indentation is allowed and strips common leading tabs

```babble
def a = """
		x
	y
	"""
def b = r"""
	\t\n
"""
assert(a == "\tx\ny\n")
assert(b == "\t\\t\\n\n")
```

## Four quotes at the end of raw block close on the first three, leaving the fourth as a new token

```babble
def s = r"""
    a""""
```

```error
Lexical error : token recognition error at: '"'
```

## Multiline literals across session inputs

```babble
;; babble session
def header = """
    SELECT *
    FROM t
    """
```

```babble
;; babble session
def body = r"""
    WHERE x = "ok"
    """
assert(header + body == "SELECT *\nFROM t\nWHERE x = \"ok\"\n")
```

## Single-line "..." rejects unescaped newline

```babble
def s = "line1
line2"
```

```error
Lexical error : token recognition error at: '"line1\n'
```

## Single-line r"..." rejects unescaped newline

```babble
def s = r"line1
line2"
```

```error
Lexical error : token recognition error at: '"line1\n'
```

## Opening """ must be followed by optional horizontal whitespace and newline

```babble
def s = """abc"""
```

```error
Lexical error : multiline string opening """ must be followed by a newline
```

## Error : Lexical error : multiline string opening """ must be followed by a ne...

```babble
def s = r"""abc"""
```

```error
Lexical error : multiline string opening """ must be followed by a newline
```

## Error : Lexical error : multiline string opening """ must be followed by a ne... (2)

```babble
def s = """  abc
    """
```

```error
Lexical error : multiline string opening """ must be followed by a newline
```

## Mixed leading tabs and spaces within a line

```babble
def s = """
 	hello
 """
```

```error
Lexical error : mixed tabs and spaces in multiline string indentation
```

## Mixed leading tabs and spaces across lines (and with closing line)

```babble
def s = r"""
    hello
	world
    """
```

```error
Lexical error : mixed tabs and spaces in multiline string indentation
```

## Error : Lexical error : mixed tabs and spaces in multiline string indentation

```babble
def s = """
    hello
	"""
```

```error
Lexical error : mixed tabs and spaces in multiline string indentation
```

## Unknown escape sequences in multiline strings

```babble
def s = """
    hello \q
    """
```

```error
Lexical error : unknown escape sequence : \q
```

## Error : Lexical error : unknown escape sequence : \u12

```babble
def s = """
    \u12
    """
```

```error
Lexical error : unknown escape sequence : \u12
```

## Error : Lexical error : unknown escape sequence : \u12g0

```babble
def s = """
    \u12g0
    """
```

```error
Lexical error : unknown escape sequence : \u12g0
```

## Unterminated multiline literals

```babble
def s = """
    unclosed
```

```error
Lexical error : unterminated multiline string
```

## Error : Lexical error : unterminated multiline string

```babble
def s = r"""
    unclosed
```

```error
Lexical error : unterminated multiline string
```

## Escaped quote right before two quotes leaves literal unterminated

```babble
def s = """
    escaped\"""
```

```error
Lexical error : unterminated multiline string
```
