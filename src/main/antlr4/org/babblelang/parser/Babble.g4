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

packageStatement: ('package' | 'paquet') ID '(' statement* ')';

ifStatement: ('if' | 'si') expression thenBlock=block
             ( ('else' | 'sinon') elseBlock=block)?;

defStatement: ('def' | 'soit') ID (':' type)? ('=' expression)?;

returnStatement: ('return' | 'retourne') expression;

whileStatement: ('while' | 'tant' 'que') expression block;

expression: '(' expression ')'                               # parenthesis
          | expression '.' ID                                # selector
          | expression callParameters                        # call
          | NOT expression                                   # booleanNot
          | left=expression op=('*' | '/') right=expression  # binaryOp
          | left=expression op=('+' | '-') right=expression  # binaryOp
          | left=expression op=('<' | '<=' | '=='
                       | '>=' | '>') right=expression        # binaryOp
          | left=expression op=AND right=expression        # booleanOp
          | left=expression op=OR right=expression         # booleanOp
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
AND: 'and'|'et';
OR: 'or' | 'ou';
NULL: 'null' | 'vide';
NOT: 'not' | 'non';
BOOLEAN: 'true' | 'false' | 'vrai' | 'faux';
ID: [_a-zA-Z] [_a-zA-Z0-9]*;
