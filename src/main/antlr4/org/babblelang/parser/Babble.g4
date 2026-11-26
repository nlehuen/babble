grammar Babble;

// Zero @parser::members. Zero semantic predicates. Single bracket type: ().

file: sep? expression (sep expression)* sep? EOF;

// Statements are separated by newlines or semicolons.
// Newlines are ignored only after tokens that require a continuation
// (operators, commas, '=', '->', 'then', ...), never between an
// expression and a following '(' : that would be ambiguous with a call.
sep: (';' | NL)+;

coreCollectionKeyword: LIST | SET | SORTEDSET | MAP | SORTEDMAP | ENTRY | ARRAY;

coreScalarKeyword: BYTE | SHORT | INT_KW | LONG_KW | FLOAT_KW | DOUBLE_KW | BOOL_KW | CHAR_KW | STRING_KW;

coreTypeKeyword: coreScalarKeyword | coreCollectionKeyword | RANGE_KW | EXCEPTION | ANY | VOID;

effectModifier: PURE | VOLATILE | IO | MUT | SHARED | NOTHROW
              | IDEMPOTENT | COMMUTATIVE | ASSOCIATIVE | MONOTONE | INJECTIVE;

memberName: ID | coreTypeKeyword | effectModifier | AXIOM | RECORD | OF | UNIQUE | FROZEN | IN | BREAK | CONTINUE | CALLING | TYPE | OVERLOAD | AND | OR | NOT | FOREVER | TRY | CATCH | FINALLY | THROW | VAR | MOD;

effectPrefix: effectWord+ callingClause?
            | callingClause;

callingClause: CALLING (ID | '(' NL* ID (NL* ',' NL* ID)* NL* ')');

effectWord: AXIOM | effectModifier;

axiomTarget: (ID | coreScalarKeyword) ('.' memberName)*;

axiomEffectWord: effectModifier | UNIQUE | ELLIPSIS | ID;

axiomEffectPrefix: axiomEffectWord+ callingClause?
                 | callingClause;

axiomType: FN (OF typeParameters)? axiomEffectPrefix? fnTypeParameters? (':' uniqueReturn=UNIQUE? type)?
         | CLASS classWords+=ID* NL* '(' axiomMembers ')' (NL* STATIC NL* '(' axiomMembers ')')?;

axiomMembers: sep? (axiomMember (sep axiomMember)* sep?)?;

axiomMember: DEF (memberName | '*') ':' axiomType;

expression:
          ifExpr                                              # ifExpression
          | (EXPORT | PRIVATE)? (DEF | VAR) name=(ID | MOD) (OF typeParameters)? (':' type)?
                     ('=' NL* value=expression)?   # defExpression
          | EXPORT? AXIOM target=axiomTarget (OF typeParameters)? ':' axiomType   # axiomExpression
          | TYPE NL* aliased=type                             # typeAliasExpression
          | WHILE test=expression THEN NL* whileBlock=block   # whileExpression
          | FOR name=ID (':' type)? IN NL* iterable=expression THEN NL* forBlock=block # forExpression
          | TRY NL* tryBlock=block
            ( (NL* catchClause)+ (NL* FINALLY NL* finallyBlock=block)?
            | NL* FINALLY NL* finallyBlock=block
            )                                                 # tryExpression
          | (CLASS | RECORD) classParameters (IMPLEMENTS typeList)? NL* classBlock=block
                (NL* STATIC NL* staticBlock=block)?           # classExpression
          | INTERFACE (EXTENDS typeList)? NL* interfaceBlock=block
                (NL* STATIC NL* staticBlock=block)?           # interfaceExpression
          | OBJECT (IMPLEMENTS typeList)? NL* createBlock=block   # objectExpression
          | FN (OF typeParameters)? effectPrefix? fnParameters (':' uniqueReturn=UNIQUE? type)? '->' NL* functionBlock=block  # functionLiteral
          | OVERLOAD '(' NL* expression (NL* ',' NL* expression)* NL* ')'   # overloadExpression
          | THIS                                              # thisExpression
          | SUPER ('(' NL* qualifier=type NL* ')')?           # superExpression
          // A newline may come before the dot : a chain of calls can go on the next lines.
          | expression NL* ('.' | safe='?.') memberName      # selector
          | ID                                                # selector
          | expression '!'                                    # nonNullAssertion
          | coreCollectionKeyword                             # coreCollection
          | coreScalarKeyword                                 # coreScalarRef
          | EXCEPTION                                         # coreExceptionRef
          | expression OF typeArguments                       # typeApply
          | expression callParameters                         # call
          | block                                             # blockExpression
          // Unary minus binds tighter than any binary operator : "-a * b" is "(-a) * b". A
          // newline before it separates : "a" then "-b" on the next line are two expressions.
          | '-' operand=expression                            # negate
          | left=expression AS NL* right=type                     # asExpression
          | left=expression op=(RANGE | RANGE_EXCL) NL* right=expression # rangeExpression
          | left=expression op=('*' | '/' | FLOOR_DIV | MOD) NL* right=expression # binaryOp
          | left=expression op=('+' | '-') NL* right=expression   # binaryOp
          | left=expression IS NL* right=type                     # isExpression
          | left=expression op=('<' | '<=' | '==' | NEQ
                       | '>=' | '>') NL* right=expression         # binaryOp
          | NOT expression                                    # booleanNot
          | left=expression op=AND NL* right=expression           # booleanOp
          | left=expression op=OR NL* right=expression            # booleanOp
          // Compound assignment : "x += v" is "x = x + v" (and -=, *=, /=, //=), with the target's
          // receiver evaluated once.
          | <assoc=right> lhs=expression op=('=' | '+=' | '-=' | '*=' | '/=' | FLOOR_DIV_ASSIGN) NL* value=expression # assignExpression
          | NULL                                              # null
          | BOOLEAN                                           # boolean
          | RECURSE                                           # recurse
          | LONG_LIT                                          # longLiteral
          | INT                                               # integer
          | FLOAT_LIT                                         # floatLiteral
          | DOUBLE_LIT                                        # double
          | CHAR_LIT                                          # charLiteral
          | (STRING | MULTILINE_STRING | RAW_STRING | RAW_MULTILINE_STRING) # string
          // "return" and "break" are prefix alternatives, so their operands are parsed at this
          // alternative's precedence. They must therefore come last, or "return a + b" / "break a + b"
          // would parse as "(return a) + b".
          | RETURN expression                                 # returnExpression
          // "forever" acknowledges an expression that never ends (a loop or a recursion
          // without an exit, docs/design/bip024_tail-calls.md) : its value is the expression's.
          | FOREVER expression                                # foreverExpression
          | BREAK expression?                                 # breakExpression
          | CONTINUE                                          # continueExpression
          | THROW expression                                  # throwExpression
          ;

catchClause: CATCH (NL* name=ID)? (':' type)? THEN NL* catchBlock=block;

ifExpr: IF test=expression THEN NL* thenBlock=block
        (NL* ELSE NL* (elseIf=ifExpr | elseBlock=block))?;

block: '(' sep? ')'
     | '(' sep? expression (sep expression)* sep? ')';

classParameters: '(' NL* classParam (NL* ',' NL* classParam)* (NL* ',' NL* kwSep=ELLIPSIS (NL* ',' NL* kwParam+=classParam)*)? NL* ')'
               | '(' NL* kwSep=ELLIPSIS (NL* ',' NL* kwParam+=classParam)* NL* ')'
               | '(' NL* ')';

classParam: PRIVATE? (DEF | VAR)? paramModifier* ID (':' type)? ('=' NL* defaultValue=expression)?;

fnParameters: '(' NL* fnParam (NL* ',' NL* fnParam)* (NL* ',' NL* kwSep=ELLIPSIS (NL* ',' NL* kwParam+=fnParam)*)? NL* ')'
            | '(' NL* kwSep=ELLIPSIS (NL* ',' NL* kwParam+=fnParam)* NL* ')'
            | '(' NL* ')';

fnParam: VAR? paramModifier* ID (':' type rest=(ELLIPSIS | Q_ELLIPSIS)?)? ('=' NL* defaultValue=expression)?;

fnTypeParameters: '(' NL* fnTypeParam (NL* ',' NL* fnTypeParam)* (NL* ',' NL* kwSep=ELLIPSIS (NL* ',' NL* kwParam+=fnTypeParam)*)? NL* ')'
                | '(' NL* kwSep=ELLIPSIS (NL* ',' NL* kwParam+=fnTypeParam)* NL* ')'
                | '(' NL* ')';

fnTypeParam: paramModifier* (ID ':')? type rest=(ELLIPSIS | Q_ELLIPSIS)? ('=' NL* optional=ELLIPSIS)?;

paramModifier: MUT | SHARED | UNIQUE;

typeParameters: ID
              | '(' NL* typeParam (NL* ',' NL* typeParam)* NL* ')';

typeParam: ID (':' type)?;

typeArguments: '(' NL* type (NL* ',' NL* type)* NL* ')'
             | nonParenNullableType;

callParameters: '(' NL* callParameter (NL* ',' NL* callParameter)* NL* ')'
              | '(' NL* ')';

callParameter: (memberName ':')? spread=ELLIPSIS? expression;

typeList: type (NL* ',' NL* type)*;

// '&' (intersection, BIP022) binds tighter than '|' (union) : TypeAnnotations groups the operands.
type: nullableType (('|' | '&') NL* nullableType)*;

nullableType: nonParenNullableType                                                    # nonParenType
            | frozen=FROZEN? '(' NL* type NL* ')' nullMod=('?' | '!')?                # parenType
            ;

nonParenNullableType: frozen=FROZEN? typeName ( OF ( '(' NL* type (NL* ',' NL* type)* NL* ')' nullMod=('?' | '!')?
                                                   | nonParenNullableType )
                                              | nullMod=('?' | '!')? )                # simpleType
                    | FN (OF typeParameters)? effectPrefix? fnTypeParameters (':' uniqueReturn=UNIQUE? type)? # functionType
                    | OVERLOAD '(' NL* type (NL* ',' NL* type)* NL* ')'               # overloadType
                    | NULL                                                            # nullType
                    ;

typeName: coreTypeKeyword
        | ID ('.' memberName)*
        ;

// Tokens
LONG_LIT: [0-9]+ [lL];
FLOAT_LIT: ([0-9]+ '.' [0-9]+ ([eE] [+-]? [0-9]+)? | [0-9]+ ([eE] [+-]? [0-9]+)) [fF]
         | [0-9]+ [fF];
DOUBLE_LIT: [0-9]+ '.' [0-9]+ ([eE] [+-]? [0-9]+)? [dD]?
          | [0-9]+ [eE] [+-]? [0-9]+ [dD]?
          | [0-9]+ [dD];
INT: [0-9]+;
CHAR_LIT: '\'' ( ~['\\\r\n] | '\\' ( [btnfr"'\\0] | 'u' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) ) '\'';
STRING: '"' ( ~[\\"\r\n] | '\\' ( [btnfr"'\\0] | 'u' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) )* '"';
RAW_STRING: 'r"' ~["\r\n]* '"';
MULTILINE_STRING
    : '"""'
      ( ~[\\"]
      | '"' ( ~[\\"] | '\\' . )
      | '""' ( ~[\\"] | '\\' . )
      | '\\' .
      )*
      '"""'
    ;
RAW_MULTILINE_STRING
    : 'r"""'
      ( ~["]
      | '"' ~["]
      | '""' ~["]
      )*
      '"""'
    ;
UNTERMINATED_MULTILINE_STRING
    : '"""'
      ( ~[\\"]
      | '"' ( ~[\\"] | '\\' . )
      | '""' ( ~[\\"] | '\\' . )
      | '\\' .
      )*
      ( '""' '\\'? | '"' '\\'? | '\\' )?
    ;
UNTERMINATED_RAW_MULTILINE_STRING
    : 'r"""'
      ( ~["]
      | '"' ~["]
      | '""' ~["]
      )*
      ( '""' | '"' )?
    ;
PLUS: '+';
MINUS: '-';
MUL: '*';
FLOOR_DIV_ASSIGN: '//=';
FLOOR_DIV: '//';
DIV: '/';
LT: '<';
LTE: '<=';
EQ: '==';
NEQ: '<>' | '!=';
GTE: '>=';
GT: '>';
BANG: '!';
COLON: ':';
SEMICOLON: ';';
ASSIGN: '=';
PLUS_ASSIGN: '+=';
MINUS_ASSIGN: '-=';
MUL_ASSIGN: '*=';
DIV_ASSIGN: '/=';
Q_ELLIPSIS: '?...';
ELLIPSIS: '...';
RANGE_EXCL: '..<';
RANGE: '..';
DOT: '.';
SAFE_DOT: '?.';
ARROW: '->';
PIPE: '|';
AMP: '&';
QUESTION: '?';
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
IF: 'if';
THEN: 'then';
ELSE: 'else';
DEF: 'def';
VAR: 'var';
RETURN: 'return';
FOREVER: 'forever';
BREAK: 'break';
CONTINUE: 'continue';
TRY: 'try';
CATCH: 'catch';
FINALLY: 'finally';
THROW: 'throw';
WHILE: 'while';
FOR: 'for';
IN: 'in';
MOD: 'mod';
AND: 'and';
OR: 'or';
NULL: 'null';
NOT: 'not';
BOOLEAN: 'true' | 'false';
RECURSE: 'recurse';
OBJECT: 'object';
CLASS: 'class';
RECORD: 'record';
TYPE: 'type';
INTERFACE: 'interface';
IMPLEMENTS: 'implements';
EXTENDS: 'extends';
IS: 'is';
AS: 'as';
THIS: 'this';
SUPER: 'super';
PRIVATE: 'private';
STATIC: 'static';
EXPORT: 'export';
FN: 'fn';
OVERLOAD: 'overload';
OF: 'of';
CALLING: 'calling';
AXIOM: 'axiom';
PURE: 'pure';
VOLATILE: 'volatile';
IO: 'io';
MUT: 'mut';
SHARED: 'shared';
UNIQUE: 'unique';
FROZEN: 'frozen';
NOTHROW: 'nothrow';
IDEMPOTENT: 'idempotent';
COMMUTATIVE: 'commutative';
ASSOCIATIVE: 'associative';
MONOTONE: 'monotone';
INJECTIVE: 'injective';
BYTE: 'byte';
SHORT: 'short';
INT_KW: 'int';
LONG_KW: 'long';
FLOAT_KW: 'float';
DOUBLE_KW: 'double';
BOOL_KW: 'bool';
CHAR_KW: 'char';
STRING_KW: 'string';
LIST: 'list';
SET: 'set';
SORTEDSET: 'sortedset';
MAP: 'map';
SORTEDMAP: 'sortedmap';
ENTRY: 'entry';
ARRAY: 'array';
RANGE_KW: 'range';
EXCEPTION: 'exception';
ANY: 'any';
VOID: 'void';
ID: [_a-zA-Z] [_a-zA-Z0-9]*;
// Comments are kept, on the hidden channel : the parser does not see them, the formatter does.
MULTILINECOMMENT: ';;(' .*? ';;)' -> channel(HIDDEN);
COMMENT: ';;' (~[(\r\n] ~[\r\n]*)? -> channel(HIDDEN);
NL: '\r'? '\n';
WS: [ \t]+ -> skip;
