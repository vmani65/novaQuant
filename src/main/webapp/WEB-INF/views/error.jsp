<%@ page isErrorPage="true" %>
<html>
<head><title>Error</title></head>
<body>
    <h2>Unexpected Error</h2>
    <p>${requestScope['jakarta.servlet.error.message']}</p>
</body>
</html>
