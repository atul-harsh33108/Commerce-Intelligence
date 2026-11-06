import uuid

from fastapi import FastAPI, Request

from app.api.routers.query_router import query_router
from app.core.context import request_id_ctx_var
from app.core.lifespan import lifespan

# Create FastAPI app and register lifespan handler
app = FastAPI(lifespan=lifespan) 

# Register routes
app.include_router(query_router)


@app.get("/health")
async def health():
    return {"status": "ok"}


# Add middleware to generate a unique request_id for each request
@app.middleware("http")
async def add_process_time_header(request: Request, call_next):
    # Before calling route handler
    request_id_ctx_var.set(uuid.uuid4())
    # Call route handler
    response = await call_next(request)
    # After calling route handler
    return response


if __name__ == '__main__':
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
