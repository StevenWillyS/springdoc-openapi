package org.springdoc.core;

import io.swagger.v3.oas.models.Operation;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ServerWebExchange;

@Component
public class RequestBuilder extends AbstractRequestBuilder {

    public RequestBuilder(AbstractParameterBuilder parameterBuilder, RequestBodyBuilder requestBodyBuilder,
                          OperationBuilder operationBuilder) {
        super(parameterBuilder, requestBodyBuilder, operationBuilder);
    }

    static {
        PARAM_TYPES_TO_IGNORE.add(ServerWebExchange.class);
    }

    @Override
    protected Operation customiseOperation(Operation operation, HandlerMethod handlerMethod) {
        return operation;
    }
}
