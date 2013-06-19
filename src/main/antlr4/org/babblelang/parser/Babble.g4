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

packageStatement: PACKAGE ID '(' statement* ')';

ifStatement: IF expression thenBlock=block
             ( ELSE elseBlock=block)?;

defStatement: DEF ID (':' type)? ('=' expression)?;

returnStatement: RETURN expression;

whileStatement: WHILE expression block;

expression: '(' expression ')'                               # parenthesis
          | expression '.' ID                                # selector
          | expression callParameters                        # call
          | NOT expression                                   # booleanNot
          | left=expression op=('*' | '/') right=expression  # binaryOp
          | left=expression op=('+' | '-') right=expression  # binaryOp
          | left=expression op=('<' | '<=' | '=='
                       | '>=' | '>') right=expression        # binaryOp
          | left=expression op=AND right=expression          # booleanOp
          | left=expression op=OR right=expression           # booleanOp
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
PLUS: '+';
MINUS: '-';
MUL: '*';
DIV: '/';
LT: '<';
LTE: '<=';
EQ: '==';
GTE: '>=';
GT: '>';
PACKAGE: 'package' | 'paquet';
IF: 'if' | 'si';
ELSE: 'else' | 'sinon';
DEF: 'def' | 'soit';
RETURN: 'return' | 'retourne';
WHILE: 'while' | 'tant' WS* 'que';
AND: 'and' | 'et';
OR: 'or' | 'ou';
NULL: 'null' | 'vide';
NOT: 'not' | 'non';
BOOLEAN: 'true' | 'false' | 'vrai' | 'faux';
ID: [_a-zA-Z] [_a-zA-Z0-9]*;
WS: [ \t\r\n]+ -> skip;
