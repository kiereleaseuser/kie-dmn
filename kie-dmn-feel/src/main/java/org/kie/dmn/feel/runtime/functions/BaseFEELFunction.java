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

package org.kie.dmn.feel.runtime.functions;

import org.kie.dmn.feel.lang.EvaluationContext;
import org.kie.dmn.feel.lang.Symbol;
import org.kie.dmn.feel.runtime.FEELFunction;
import org.kie.dmn.feel.lang.impl.NamedParameter;
import org.kie.dmn.feel.lang.types.FunctionSymbol;
import org.kie.dmn.feel.runtime.decisiontables.ConcreteDTFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class BaseFEELFunction implements FEELFunction {

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private String name;
    private Symbol symbol;

    public BaseFEELFunction( String name ) {
        this.name = name;
        this.symbol = new FunctionSymbol( name, this );
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName( String name ) {
        this.name = name;
        ((FunctionSymbol)this.symbol).setId( name );
    }

    @Override
    public Symbol getSymbol() {
        return symbol;
    }

    @Override
    public Object applyReflectively(EvaluationContext ctx, Object[] params) {
        // use reflection to call the appropriate apply method
        try {
            boolean isNamedParams = params.length > 0 && params[0] instanceof NamedParameter;
            if ( ! isCustomFunction() ) {
                List<String> available = null;
                if( isNamedParams ) {
                    available = Stream.of( params ).map( p -> ((NamedParameter)p).getName() ).collect( Collectors.toList() );
                }

                Class[] classes = Stream.of( params ).map( p -> p != null ? p.getClass() : null ).toArray( Class[]::new );

                CandidateMethod cm = getCandidateMethod( params, isNamedParams, available, classes );

                if( cm != null ) {
                    Object result = cm.apply.invoke( this, cm.actualParams );
                    return result;
                } else {
                    String ps = Arrays.toString( classes );
                    logger.error( "Unable to find function '" + getName() + "( " + ps.substring( 1, ps.length()-1 ) +" )'" );
                }
            } else {
                Object result = null;
                if( this instanceof CustomFEELFunction ) {
                    if( isNamedParams ) {
                        params = rearrangeParameters( params, ((CustomFEELFunction) this).getParameterNames().get( 0 ) );
                    }
                    result = ((CustomFEELFunction)this).apply( ctx, params );
                } else if( this instanceof JavaFunction ) {
                    if( isNamedParams ) {
                        params = rearrangeParameters( params, ((JavaFunction) this).getParameterNames().get( 0 ) );
                    }
                    result = ((JavaFunction)this).apply( ctx, params );
                } else if( this instanceof ConcreteDTFunction ) {
                    if( isNamedParams ) {
                        params = rearrangeParameters( params, ((ConcreteDTFunction) this).getParameterNames().get( 0 ) );
                    }
                    result = ((ConcreteDTFunction)this).apply( ctx, params );
                } else {
                    logger.error( "Unable to find function '" + toString() +"'" );
                }
                return normalizeResult( result );
            }
        } catch ( Exception e ) {
            logger.error( "Error trying to call function "+getName()+".", e );
        }
        return null;
    }

    private Object[] rearrangeParameters(Object[] params, List<String> pnames) {
        if( pnames.size() > 0 ) {
            Object[] actualParams = new Object[pnames.size()];
            for( int i = 0; i < actualParams.length; i++ ) {
                for( int j = 0; j < params.length; j++ ) {
                    if( ((NamedParameter)params[j]).getName().equals( pnames.get( i ) ) ) {
                        actualParams[i] = ((NamedParameter)params[j]).getValue();
                        break;
                    }
                }
            }
            params = actualParams;
        }
        return params;
    }

    private CandidateMethod getCandidateMethod(Object[] params, boolean isNamedParams, List<String> available, Class[] classes) {
        CandidateMethod candidate = null;
        // first, look for exact matches
        for( Method m : getClass().getDeclaredMethods() ) {
            if( !m.getName().equals( "apply" ) ) {
                continue;
            }
            CandidateMethod cm = new CandidateMethod( isNamedParams ? calculateActualParams( m, params, available ) : params );

            Class<?>[] parameterTypes = m.getParameterTypes();
            adjustForVariableParameters( cm, parameterTypes );

            if( parameterTypes.length != cm.getActualParams().length  ) {
                continue;
            }

            boolean found = true;
            for( int i = 0; i < parameterTypes.length; i++ ) {
                if ( cm.getActualClasses()[i] != null && ! parameterTypes[i].isAssignableFrom( cm.getActualClasses()[i] ) ) {
                    found = false;
                    break;
                }
            }
            if( found ) {
                cm.setApply( m );
                if( candidate == null || cm.getScore() > candidate.getScore() ) {
                    candidate = cm;
                }
            }
        }
        return candidate;
    }

    private void adjustForVariableParameters(CandidateMethod cm, Class<?>[] parameterTypes) {
        if( parameterTypes.length > 0 && parameterTypes[parameterTypes.length-1].isArray() ) {
            // then it is a variable parameters function call
            Object[] newParams = new Object[parameterTypes.length];
            if( newParams.length > 1 ) {
                System.arraycopy( cm.getActualParams(), 0, newParams, 0, newParams.length-1 );
            }
            Object[] remaining = new Object[cm.getActualParams().length-parameterTypes.length+1];
            newParams[newParams.length-1] = remaining;
            System.arraycopy( cm.getActualParams(), parameterTypes.length-1, remaining, 0, remaining.length );
            cm.setActualParams( newParams );
        }
    }

    private Object[] calculateActualParams( Method m, Object[] params, List<String> available ) {
        Annotation[][] pas = m.getParameterAnnotations();
        List<String> names = new ArrayList<>( m.getParameterCount() );
        for( int i = 0; i < m.getParameterCount(); i++ ) {
            for( int p = 0; p < pas[i].length; i++ ) {
                if( pas[i][p] instanceof ParameterName ) {
                    names.add( ((ParameterName)pas[i][p]).value() );
                    break;
                }
            }
            if( names.get( i ) == null ) {
                // no name found
                return null;
            }
        }
        if( names.containsAll( available ) ) {
            Object[] actualParams = new Object[names.size()];
            for( Object o : params ) {
                NamedParameter np = (NamedParameter) o;
                actualParams[ names.indexOf( np.getName() ) ] = np.getValue();
            }
            return actualParams;
        } else {
            // method is not compatible
            return null;
        }
    }

    private Object normalizeResult(Object result) {
        // this is to normalize types returned by external functions
        return result != null && result instanceof Number && !(result instanceof BigDecimal) ? new BigDecimal( result.toString() ) : result;
    }

    protected boolean isCustomFunction() {
        return false;
    }


    private static class CandidateMethod {
        private Method apply = null;
        private Object[] actualParams = null;
        private Class[] actualClasses = null;
        private int score;

        public CandidateMethod(Object[] actualParams) {
            this.actualParams = actualParams;
            populateActualClasses();
        }

        private void calculateScore() {
            if( actualClasses.length > 0 && actualClasses[ actualClasses.length-1 ] != null && actualClasses[ actualClasses.length-1 ].isArray() ) {
                score = 1;
            } else {
                score = 10;
            }
        }

        public Method getApply() {
            return apply;
        }

        public void setApply(Method apply) {
            this.apply = apply;
            calculateScore();
        }

        public Object[] getActualParams() {
            return actualParams;
        }

        public void setActualParams(Object[] actualParams) {
            this.actualParams = actualParams;
            populateActualClasses();
        }

        private void populateActualClasses() {
            this.actualClasses = Stream.of( this.actualParams ).map( p -> p != null ? p.getClass() : null ).toArray( Class[]::new );
        }

        public Class[] getActualClasses() {
            return actualClasses;
        }

        public int getScore() {
            return score;
        }

    }

}
