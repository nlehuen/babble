grammar Babble;

file: statement* EOF;

statement: packageStatement
         | ifStatement
         | whileStatement
         | defStatement
         | returnStatement
         | block
         | expression
         | ';';

packageStatement: 'package' ID '(' statement* ')';

ifStatement: 'if' expression thenBlock=block
             ( 'else' elseBlock=block)?;

defStatement: 'def' ID (':' type)? ('=' expression)?;

returnStatement: 'return' expression;

whileStatement: 'while' expression block;

expression: '(' expression ')'                               # parenthesis
          | expression '.' ID                                # selector
          | expression callParameters                        # call
          | NOT expression                                   # booleanNot
          | left=expression op=('*' | '/') right=expression  # binaryOp
          | left=expression op=('+' | '-') right=expression  # binaryOp
          | left=expression op=('<' | '<=' | '=='
                       | '>=' | '>') right=expression        # binaryOp
          | left=expression op='and' right=expression        # booleanOp
          | left=expression op='or' right=expression         # booleanOp
          | functionType '->' block                          # functionLiteral
          | ID '=' expression                                # assign
          | NULL                                             # null
          | BOOLEAN                                          # boolean
          | ID                                               # id
          | INT                                              # integer
          | FLOAT                                            # double
          | STRING                                           # string
          ;

block: '(' ')'
     | '(' statement+ ')'
     ;


parametersDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')'
                     | '(' ')';

parameterDeclaration: ID (':' type)?;

callParameters: '(' callParameter (',' callParameter)* ')'
              | '(' ')';

callParameter: (ID ':')? expression;

type: simpleType
    | type parametersDeclaration
    | functionType
    ;

simpleType: ID ('.' ID)*;

functionType: parametersDeclaration ( ':' type | );


// Tokens
INT: [0-9]+;
FLOAT: [0-9]* '.' [0-9]+ ('E' [0-9]+)?;
STRING: '"' (~[\\"]|'\\\\'|'\\"')*? '"';
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
AND: 'and';
OR: 'or';
NULL: 'null';
NOT: 'not';
BOOLEAN: 'true' | 'false';
ID: [a-zA-Z] [a-zA-Z0-9]*;
