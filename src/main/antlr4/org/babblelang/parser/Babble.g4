grammar Babble;

file: statement* EOF;

statement: packageStatement
         | ifStatement
         | whileStatement
         | defStatement
         | assignStatement
         | returnStatement
         | block
         | expression
         | ';';

packageStatement: 'package' ID block;

ifStatement: 'if' expression block
             ( 'else' block)?;

defStatement: 'def' ID (':' type)? ('=' expression)?;

assignStatement: ID '=' expression;

returnStatement: 'return' expression;

whileStatement: 'while' expression block;

expression: '(' expression ')'                    # paren
          | expression op=('*' | '/') expression  # binaryOp
          | expression op=('+' | '-') expression  # binaryOp
          | expression op=('<' | '<=' | '==' | '>=' | '>') expression  # binaryOp
          | functionLiteral                       # fun
          | expression callParameters             # call
          | selector                              # sel
          | INT                                   # number
          | FLOAT                                 # number
          | STRING                                # string
          ;

selector: ID
        | selector '.' ID;

block: '(' ')'
     | '(' statement+ ')'
     ;

functionLiteral: functionType '->' block;

functionType: parametersDeclaration returnTypeDeclaration;

parametersDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')'
                     | '(' ')';

parameterDeclaration: ID ':' type;

returnTypeDeclaration: ':' type
                     | ;

callParameters: '(' callParameter (',' callParameter)* ')'
              | '(' ')';

callParameter: (ID ':')? expression;

type: ID
    | ID '(' type (',' type)* ')'
    | functionType;

// Tokens
ID: [a-zA-Z] [a-zA-Z0-9]*;
INT: [0-9]+;
FLOAT: [0-9]* '.' [0-9]+ ('E' [0-9]+)?;
STRING: '"' .*? '"';
WS: [ \t]+ -> skip;
NL: '\r'? '\n' -> skip;
PLUS: '+';
MINUS: '-';
MUL: '*';
DIV: '/';
LT: '<';
LTE: '<=';
EQ: '==';
GTE: '>=';
GT: '>';