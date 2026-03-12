class GatewayError(Exception):
    def __init__(self, message: str, code: int = 502):
        self.message = message
        self.status_code = code
        super().__init__(message)


class NotFoundError(Exception):
    def __init__(self, message: str = "Not found"):
        self.message = message
        super().__init__(message)


class ValidationError(Exception):
    def __init__(self, message: str):
        self.message = message
        super().__init__(message)


class PermissionDeniedError(Exception):
    def __init__(self, message: str = "Permission denied"):
        self.message = message
        super().__init__(message)