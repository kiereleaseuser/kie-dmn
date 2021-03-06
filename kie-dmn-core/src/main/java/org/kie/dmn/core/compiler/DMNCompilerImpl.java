/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.dmn.core.compiler;

import org.kie.api.io.Resource;
import org.kie.dmn.backend.marshalling.v1_1.DMNMarshallerFactory;
import org.kie.dmn.core.api.DMNCompiler;
import org.kie.dmn.core.api.DMNModel;
import org.kie.dmn.core.api.DMNType;
import org.kie.dmn.core.ast.DecisionNode;
import org.kie.dmn.core.ast.InputDataNode;
import org.kie.dmn.core.ast.ItemDefNode;
import org.kie.dmn.core.impl.CompositeTypeImpl;
import org.kie.dmn.core.impl.DMNModelImpl;
import org.kie.dmn.core.impl.FeelTypeImpl;
import org.kie.dmn.feel.FEEL;
import org.kie.dmn.feel.lang.CompiledExpression;
import org.kie.dmn.feel.lang.CompilerContext;
import org.kie.dmn.feel.lang.Type;
import org.kie.dmn.feel.lang.types.BuiltInType;
import org.kie.dmn.feel.model.v1_1.*;
import org.kie.dmn.feel.model.v1_1.DecisionRule;
import org.kie.dmn.feel.model.v1_1.List;
import org.kie.dmn.feel.runtime.Range;
import org.kie.dmn.feel.runtime.UnaryTest;
import org.kie.dmn.feel.runtime.decisiontables.*;
import org.kie.dmn.feel.runtime.decisiontables.HitPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.util.*;

public class DMNCompilerImpl implements DMNCompiler {

    private static final Logger logger = LoggerFactory.getLogger( DMNCompilerImpl.class );

    @Override
    public DMNModel compile(Resource resource) {
        try {
            return compile( resource.getReader() );
        } catch ( IOException e ) {
            logger.error( "Error retrieving reader for resource: "+resource.getSourcePath(), e );
        }
        return null;
    }

    @Override
    public DMNModel compile(Reader source) {
        try {
            Definitions dmndefs = DMNMarshallerFactory.newDefaultMarshaller().unmarshal( source );
            if ( dmndefs != null ) {
                DMNModelImpl model = new DMNModelImpl( dmndefs );

                processItemDefinitions( model, dmndefs );
                processDrgElements( model, dmndefs );
                return model;
            }
        } catch ( Exception e ) {
            logger.error( "Error compiling model from source.", e );
        }
        return null;
    }

    private void processItemDefinitions(DMNModelImpl model, Definitions dmndefs) {
        for( ItemDefinition id : dmndefs.getItemDefinition() ) {
            DMNType type = buildTypeDef( id );
            ItemDefNode idn = new ItemDefNode( id, type );
            model.addItemDefinition( idn );
        }
    }

    private void processDrgElements(DMNModelImpl model, Definitions dmndefs) {
        for ( DRGElement e : dmndefs.getDrgElement() ) {
            if ( e instanceof InputData ) {
                InputDataNode idn = new InputDataNode( (InputData) e );
                model.addInput( idn );
            } else if ( e instanceof Decision ) {
                DecisionNode dn = new DecisionNode( (Decision) e );
                model.addDecision( dn );
            }
        }

        for ( DecisionNode d : model.getDecisions() ) {
            linkDecisionRequirements( model, d );
            DecisionNode.DecisionEvaluator evaluator = compileDecision( d );
            d.setEvaluator( evaluator );
        }
    }

    private void linkDecisionRequirements(DMNModelImpl model, DecisionNode decision) {
        for ( InformationRequirement ir : decision.getDecision().getInformationRequirement() ) {
            if ( ir.getRequiredInput() != null ) {
                String id = getId( ir.getRequiredInput() );
                InputDataNode input = model.getInputById( id );
                decision.addDependency( input.getName(), input );
            } else if ( ir.getRequiredDecision() != null ) {
                String id = getId( ir.getRequiredDecision() );
                DecisionNode dn = model.getDecisionById( id );
                decision.addDependency( dn.getName(), dn );
            }
        }
    }

    private String getId(DMNElementReference er) {
        String href = er.getHref();
        return href.contains( "#" ) ? href.substring( href.indexOf( '#' ) + 1 ) : href;
    }

    private DMNType buildTypeDef( ItemDefinition itemDef ) {
        DMNType type = null;
        if( itemDef.getTypeRef() != null ) {
            // this is an "simple" type, so find the namespace
            String prefix = itemDef.getTypeRef().getPrefix();
            String namespace = itemDef.getNamespaceURI( prefix );
            UnaryTests allowedValuesStr = itemDef.getAllowedValues();
            if( DMNModelInstrumentedBase.URI_FEEL.equals( namespace ) ) {
                Type feelType = BuiltInType.determineTypeFromName( itemDef.getTypeRef().getLocalPart() );
                java.util.List<?> allowedValues = null;
                if( allowedValuesStr != null ) {
                    Object av = FEEL.newInstance().evaluate( "[" + allowedValuesStr.getText() + "]" );
                    allowedValues = av instanceof java.util.List ? (java.util.List) av : Collections.singletonList( av );
                }
                type = new FeelTypeImpl( itemDef.getName(), itemDef.getId(), feelType, allowedValues );
            } else {
                logger.error( "Unknown namespace for type reference prefix: "+prefix );
            }
        } else {
            // this is a composite type
            CompositeTypeImpl compType = new CompositeTypeImpl( itemDef.getName(), itemDef.getId() );
            for( ItemDefinition fieldDef : itemDef.getItemComponent() ) {
                DMNType field = buildTypeDef( fieldDef );
                compType.getFields().put( field.getName(), field );
            }
            type = compType;
        }
        return type;
    }

    private DecisionNode.DecisionEvaluator compileDecision(DecisionNode decisionNode) {
        Decision decision = decisionNode.getDecision();
        FEEL feel = FEEL.newInstance();
        Expression expression = decision.getExpression();
        if( expression instanceof LiteralExpression ) {
            CompilerContext ctx = feel.newCompilerContext();
            decisionNode.getDependencies().forEach( (name, node) -> {
                // TODO: need to properly resolve types here
                ctx.addInputVariableType( name, BuiltInType.UNKNOWN );
            } );
            CompiledExpression compiledExpression = feel.compile( ((LiteralExpression) expression).getText(), ctx );
            DecisionNode.LiteralExpressionFEELEvaluator evaluator = new DecisionNode.LiteralExpressionFEELEvaluator( compiledExpression );
            return evaluator;
        } else if( expression instanceof DecisionTable ) {
            DecisionTable dt = (DecisionTable) expression;
            java.util.List<String> inputs = new ArrayList<>(  );
            for( InputClause ic : dt.getInput() ) {
                inputs.add( ic.getInputExpression().getText() );
            }
            java.util.List<org.kie.dmn.feel.runtime.decisiontables.DecisionRule> rules = new ArrayList<>(  );
            for( DecisionRule dr : dt.getRule() ) {
                org.kie.dmn.feel.runtime.decisiontables.DecisionRule rule = new org.kie.dmn.feel.runtime.decisiontables.DecisionRule();
                for( UnaryTests ut : dr.getInputEntry() ) {
                    // quick hack to parse values, in case they are a list
                    java.util.List<Object> ie = (java.util.List<Object>) feel.evaluate( "[ " + ut.getText() + " ]" );
                    java.util.List<UnaryTest> tests = new ArrayList<>(  );
                    for( Object o : ie ) {
                        if ( o instanceof UnaryTest ) {
                            tests.add( (UnaryTest) o );
                        } else if ( o instanceof Range ) {
                            tests.add( x -> ((Range) o).includes( (Comparable<?>) x ) );
                        } else {
                            tests.add( x -> x.equals( o ) );
                        }
                    }
                    rule.getInputEntry().add( x -> tests.stream().anyMatch( t -> t.apply( x ) ) );
                }
                for( LiteralExpression le : dr.getOutputEntry() ) {
                    Object oe = feel.evaluate( le.getText() );
                    rule.getOutputEntry().add( oe );
                }
                rules.add( rule );
            }
            ConcreteDTFunction dtf = new ConcreteDTFunction( decision.getName()+"_DT", inputs, rules, HitPolicy.fromString( dt.getHitPolicy().value() ) );
            DecisionNode.DTExpressionEvaluator dtee = new DecisionNode.DTExpressionEvaluator( dtf );
            return dtee;
        }
        return null;
    }


}