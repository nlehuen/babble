grammar Babble;

file: sep? expression (sep expression)* sep? EOF;

// Statements are separated by newlines or semicolons.
// Newlines are ignored only after tokens that require a continuation
// (operators, commas, '=', '->', 'then', ...), never between an
// expression and a following '(' : that would be ambiguous with a call.
sep: (';' | NL)+;

expression:
          // "export" is only accepted at the top level of a file, which the analysis checks.
          EXPORT? PACKAGE name=ID NL* packageBlock=block      # packageExpression
          | IF test=expression THEN NL* thenBlock=block
                     (NL* ELSE NL* elseBlock=block)?          # ifExpression
          // "else if" chains, without a block around each "if" : elseIf is an if expression,
          // which the predicate makes sure of. As the alternative ends with it, it is parsed
          // at this alternative's precedence : "… else (c) + 1" adds to the whole chain.
          | IF test=expression THEN NL* thenBlock=block
                     NL* ELSE NL* {_input.LA(1) == IF}? elseIf=expression   # ifExpression
          | (EXPORT | PRIVATE)? DEF name=ID typeParameters? (':' type)? ('=' NL* value=expression)?   # defExpression
          | WHILE test=expression THEN NL* whileBlock=block   # whileExpression
          // "value class" : the modifier is a contextual keyword, checked by the analysis.
          // No newline before the body : "class (a)" followed by "(b)" on the next line
          // would be ambiguous between a header and a body.
          | modifier=ID? CLASS parametersDeclaration? (IMPLEMENTS typeList)? classBlock=block
                (NL* STATIC NL* staticBlock=block)?           # classExpression
          | INTERFACE (EXTENDS typeList)? NL* interfaceBlock=block
                (NL* STATIC NL* staticBlock=block)?           # interfaceExpression
          | OBJECT (IMPLEMENTS typeList)? NL* createBlock=block   # objectExpression
          | THIS                                              # thisExpression
          | SUPER ('(' NL* qualifier=type NL* ')')?           # superExpression
          // A newline may come before the dot : a chain of calls can go on the next lines.
          | expression NL* ('.' | safe='?.') ID               # selector
          | ID                                                # selector
          | expression callParameters                         # call
          | block                                             # blockExpression
          | NOT expression                                    # booleanNot
          // Unary minus binds tighter than any binary operator : "-a * b" is "(-a) * b". A
          // newline before it separates : "a" then "-b" on the next line are two expressions.
          | '-' operand=expression                            # negate
          | left=expression op=('*' | '/') NL* right=expression   # binaryOp
          | left=expression op=('+' | '-') NL* right=expression   # binaryOp
          | left=expression AS NL* right=type                     # asExpression
          | left=expression op=('<' | '<=' | '==' | NEQ
                       | '>=' | '>') NL* right=expression         # binaryOp
          | left=expression IS NL* right=expression               # isExpression
          | left=expression op=AND NL* right=expression           # booleanOp
          | left=expression op=OR NL* right=expression            # booleanOp
          | parametersDeclaration ( ':' type | ) '->' NL* functionBlock=block  # functionLiteral
          // Compound assignment : "x += v" is "x = x + v" (and -=, *=, /=), with the target's
          // receiver evaluated once.
          | namespace=expression NL* '.' name=ID op=('=' | '+=' | '-=' | '*=' | '/=') NL* value=expression # assignExpression
          | name=ID op=('=' | '+=' | '-=' | '*=' | '/=') NL* value=expression   # assignExpression
          | NULL                                              # null
          | BOOLEAN                                           # boolean
          | RECURSE                                           # recurse
          | INT                                               # integer
          | FLOAT                                             # double
          | STRING                                            # string
          // "return" is a prefix alternative, so its operand is parsed at this
          // alternative's precedence. It must therefore come last, or "return a + b"
          // would parse as "(return a) + b".
          | RETURN expression                                 # returnExpression
          ;

block: '(' sep? ')'
     | '(' sep? expression (sep expression)* sep? ')';

parametersDeclaration: '(' NL* parameterDeclaration (NL* ',' NL* parameterDeclaration)* NL* ')'
                     | '(' NL* ')';

// PRIVATE and DEF are only accepted in class headers, which the analysis checks.
parameterDeclaration: PRIVATE? DEF? ID (':' type)? ('=' NL* defaultValue=expression)?;

typeParameters: '(' NL* ID (NL* ',' NL* ID)* NL* ')';

typeArguments: '(' NL* type (NL* ',' NL* type)* NL* ')';

callParameters: '(' NL* callParameter (NL* ',' NL* callParameter)* NL* ')'
              | '(' NL* ')';

callParameter: (ID ':')? expression;

typeList: type (NL* ',' NL* type)*;

type: nullableType ('|' NL* nullableType)*;

nullableType: primaryType question='?'?;

primaryType: ID ('.' ID)* typeArguments?          # simpleType
           | parametersDeclaration ( ':' type | )   # functionType
           | NULL                                   # nullType
           | '(' NL* type NL* ')'                   # parenType
           ;

// Tokens
INT: [0-9]+;
FLOAT: [0-9]* '.' [0-9]+ ('E' [0-9]+)?;
STRING: '"' (~[\\"]|'\\\\'|'\\"')*? '"';
PLUS: '+';
MINUS: '-';
MUL: '*';
DIV: '/';
LT: '<';
LTE: '<=';
EQ: '==';
NEQ: '<>' | '!=';
GTE: '>=';
GT: '>';
COLON: ':';
SEMICOLON: ';';
ASSIGN: '=';
PLUS_ASSIGN: '+=';
MINUS_ASSIGN: '-=';
MUL_ASSIGN: '*=';
DIV_ASSIGN: '/=';
DOT: '.';
SAFE_DOT: '?.';
ARROW: '->';
PIPE: '|';
QUESTION: '?';
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
PACKAGE: 'package';
IF: 'if';
THEN: 'then';
ELSE: 'else';
DEF: 'def';
RETURN: 'return';
WHILE: 'while';
AND: 'and';
OR: 'or';
NULL: 'null';
NOT: 'not';
BOOLEAN: 'true' | 'false';
RECURSE: 'recurse';
OBJECT: 'object';
CLASS: 'class';
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
ID: [_a-zA-Z] [_a-zA-Z0-9]*;
// Comments are kept, on the hidden channel : the parser does not see them, the formatter does.
MULTILINECOMMENT: ';;(' .*? ';;)' -> channel(HIDDEN);
COMMENT: ';;' ~[\r\n]* -> channel(HIDDEN);
NL: '\r'? '\n';
WS: [ \t]+ -> skip;
