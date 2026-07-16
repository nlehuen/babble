grammar Babble;

file: sep? expression (sep expression)* sep? EOF;

// Statements are separated by newlines or semicolons.
// Newlines are ignored only after tokens that require a continuation
// (operators, commas, '=', '->', 'then', ...), never between an
// expression and a following '(' : that would be ambiguous with a call.
sep: (';' | NL)+;

expression:
          PACKAGE name=ID NL* packageBlock=block              # packageExpression
          | IF test=expression THEN NL* thenBlock=block
                     (NL* ELSE NL* elseBlock=block)?          # ifExpression
          | DEF name=ID (':' type)? ('=' NL* value=expression)?   # defExpression
          | RETURN expression                                 # returnExpression
          | WHILE test=expression THEN NL* whileBlock=block   # whileExpression
          | OBJECT NL* createBlock=block                      # objectExpression
          | expression '.' ID                                 # selector
          | ID                                                # selector
          | expression callParameters                         # call
          | block                                             # blockExpression
          | NOT expression                                    # booleanNot
          | left=expression op=('*' | '/') NL* right=expression   # binaryOp
          | left=expression op=('+' | '-') NL* right=expression   # binaryOp
          | left=expression op=('<' | '<=' | '==' | NEQ
                       | '>=' | '>') NL* right=expression         # binaryOp
          | left=expression op=AND NL* right=expression           # booleanOp
          | left=expression op=OR NL* right=expression            # booleanOp
          | parametersDeclaration ( ':' type | ) '->' NL* functionBlock=block  # functionLiteral
          | namespace=expression '.' name=ID '=' NL* value=expression # assignExpression
          | name=ID '=' NL* value=expression                  # assignExpression
          | NULL                                              # null
          | BOOLEAN                                           # boolean
          | RECURSE                                           # recurse
          | INT                                               # integer
          | FLOAT                                             # double
          | STRING                                            # string
          ;

block: '(' sep? ')'
     | '(' sep? expression (sep expression)* sep? ')';

parametersDeclaration: '(' NL* parameterDeclaration (NL* ',' NL* parameterDeclaration)* NL* ')'
                     | '(' NL* ')';

parameterDeclaration: ID (':' type)? ('=' NL* defaultValue=expression)?;

callParameters: '(' NL* callParameter (NL* ',' NL* callParameter)* NL* ')'
              | '(' NL* ')';

callParameter: (ID ':')? expression;

type: ID ('.' ID)*                           # simpleType
    | parametersDeclaration ( ':' type | )   # functionType
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
DOT: '.';
ARROW: '->';
RPAREN: '(';
LPAREN: ')';
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
ID: [_a-zA-Z] [_a-zA-Z0-9]*;
MULTILINECOMMENT: ';;(' .*? ';;)' -> skip;
COMMENT: ';;' ~[\r\n]* -> skip;
NL: '\r'? '\n';
WS: [ \t]+ -> skip;
