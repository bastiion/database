/* Generated By:JJTree: Do not edit this line. ASTTrue.java */

package com.bigdata.rdf.sail.sparql.ast;

import com.bigdata.rdf.sail.sparql.ast.ASTRDFValue;
import com.bigdata.rdf.sail.sparql.ast.SyntaxTreeBuilder;
import com.bigdata.rdf.sail.sparql.ast.SyntaxTreeBuilderVisitor;
import com.bigdata.rdf.sail.sparql.ast.VisitorException;

public class ASTTrue extends ASTRDFValue {

	public ASTTrue(int id) {
		super(id);
	}

	public ASTTrue(SyntaxTreeBuilder p, int id) {
		super(p, id);
	}

	@Override
	public Object jjtAccept(SyntaxTreeBuilderVisitor visitor, Object data)
		throws VisitorException
	{
		return visitor.visit(this, data);
	}
}