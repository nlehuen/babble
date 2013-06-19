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

packageStatement: 'package' ID '(' statement* ')';

ifStatement: 'if' expression thenBlock=block
             ( 'else' elseBlock=block)?;

defStatement: 'def' ID (':' type)? ('=' expression)?;

assignStatement: ID '=' expression;

returnStatement: 'return' expression;

whileStatement: 'while' expression block;

expression: '(' expression ')'                    # parenthesis
          | expression '.' ID                     # selector
          | expression op=('*' | '/') expression  # binaryOp
          | expression op=('+' | '-') expression  # binaryOp
          | expression op=('<' | '<=' | '=='
                       | '>=' | '>') expression   # binaryOp
          | expression op='and' expression        # binaryOp
          | expression op='or' expression         # binaryOp
          | functionType '->' block               # functionLiteral
          | expression callParameters             # call
          | ID                                    # id
          | INT                                   # integer
          | FLOAT                                 # double
          | STRING                                # string
          ;

block: '(' ')'
     | '(' statement+ ')'
     ;


parametersDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')'
                     | '(' ')';

parameterDeclaration: ID ':' type;

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
ID: [a-zA-Z] [a-zA-Z0-9]*;