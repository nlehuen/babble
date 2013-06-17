grammar Babble;

file: declaration* EOF;

declaration: packageDecl
           | classDecl
           | functionDecl
           | importDecl;

packageDecl: 'package' ID '(' declaration* ')';

classDecl: 'class' ID templateDeclaration? '(' declaration* ')';

functionDecl: 'function' ID parametersDeclaration returnTypeDeclaration '->' '(' statement* ')';

importDecl: 'import' ID ( 'as' ID )?;

parametersDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')'
                     | '(' ')';

templateDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')';

returnTypeDeclaration: ':' returnTypeDeclaration
                     | ;

parameterDeclaration: ID ':' type;

statement: ifStatement
         | loopStatement
         | defStatement
         | assignStatement
         | returnStatement
         | expression;

ifStatement: 'if' expression '(' statement* ')'
             ( 'else' '(' statement* ')' )?;

callParameters: '(' callParameter (',' callParameter)* ')'
              | '(' ')';

callParameter: (ID ':')? expression;

defStatement: 'def' ID (':' type)? ('=' expression)?;

assignStatement: ID '=' expression;

returnStatement: 'return' expression;

loopStatement: 'while' expression '(' statement* ')';

expression: '(' expression ')'                   # paren
          | expression op=('*' | '/') expression # mul
          | expression op=('+' | '-') expression # add
          | expression callParameters            # call
          | expression '.' ID                    # selector
          | ID                                   # id
          | INT                                  # number
          | FLOAT                                # number
          ;
type: ID
    | ID '(' type (',' type)* ')';

ID: [a-zA-Z] [a-zA-Z0-9]*;
INT: [0-9]+;
FLOAT: [0-9]* '.' [0-9]+ ('E' [0-9]+)?;
WS: [ \t\r\n]+ -> skip;