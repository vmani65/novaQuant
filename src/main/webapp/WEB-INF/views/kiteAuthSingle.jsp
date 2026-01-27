<%@ page contentType="text/html; charset=UTF-8" %>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8"/>
    <title>NovaQuant — Kite Auth</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            margin: 40px;
            max-width: 800px;
        }
        h3 {
            color: #333;
        }
        .message {
            padding: 10px;
            margin: 10px 0;
            border-radius: 4px;
        }
        .success {
            color: green;
            background-color: #d4edda;
            border: 1px solid #c3e6cb;
        }
        .error {
            color: red;
            background-color: #f8d7da;
            border: 1px solid #f5c6cb;
        }
        .info-section {
            background-color: #f8f9fa;
            padding: 15px;
            border-radius: 5px;
            margin: 20px 0;
        }
        .info-section p {
            margin: 5px 0;
        }
        .section {
            margin: 30px 0;
            padding: 20px;
            border: 1px solid #ddd;
            border-radius: 5px;
            background-color: #fff;
        }
        .section h4 {
            margin-top: 0;
            color: #555;
        }
        input[type="text"] {
            padding: 8px;
            width: 400px;
            border: 1px solid #ccc;
            border-radius: 4px;
        }
        button {
            padding: 10px 20px;
            margin-left: 10px;
            cursor: pointer;
            background-color: #007bff;
            color: white;
            border: none;
            border-radius: 4px;
            font-size: 14px;
        }
        button:hover {
            background-color: #0056b3;
        }
        button:disabled {
            background-color: #ccc;
            cursor: not-allowed;
        }
        .login-url-display {
            margin-top: 15px;
            padding: 10px;
            background-color: #e7f3ff;
            border: 1px solid #b3d9ff;
            border-radius: 4px;
            word-break: break-all;
            display: none;
        }
        .login-url-display a {
            color: #0056b3;
            text-decoration: none;
        }
        .login-url-display a:hover {
            text-decoration: underline;
        }
        label {
            display: block;
            margin-bottom: 5px;
            font-weight: bold;
        }
    </style>
</head>
<body>
    <h3>NovaQuant — Kite Authentication</h3>

    <!-- Success/Error Messages -->
    <% if (request.getAttribute("success") != null) { %>
        <div class="message success"><%= request.getAttribute("success") %></div>
    <% } %>
    
    <% if (request.getAttribute("error") != null) { %>
        <div class="message error"><%= request.getAttribute("error") %></div>
    <% } %>

    <!-- Info Section -->
    <div class="info-section">
        <p>Today: <b><%= request.getAttribute("today") != null ? request.getAttribute("today") : "N/A" %></b></p>
        <p>Already saved? <b><%= request.getAttribute("exists") != null ? String.valueOf(request.getAttribute("exists")) : "false" %></b></p>
    </div>

    <!-- Section 1: Get Login URL -->
    <div class="section">
        <h4>Step 1: Get Kite Login URL</h4>
        <p>Click the button below to get the Kite login URL. Open the URL in your browser to authenticate.</p>
        <button id="getLoginUrlBtn" onclick="getLoginUrl()">Get Login URL</button>
        <div id="loginUrlDisplay" class="login-url-display"></div>
    </div>

    <!-- Section 2: Save Auth Token -->
    <div class="section">
        <h4>Step 2: Save Auth Token</h4>
        <p>After authentication, you'll receive a request token. Paste it below and click Save.</p>
        <form method="post" action="<%= request.getContextPath() %>/kite-auth/save">
            <label for="requestToken">Request Token:</label>
            <input type="text" id="requestToken" name="requestToken" maxlength="128" required placeholder="Enter request token here">
            <button type="submit">Save Auth Token</button>
        </form>
    </div>

    <script>
        function getLoginUrl() {
            const btn = document.getElementById('getLoginUrlBtn');
            const display = document.getElementById('loginUrlDisplay');
            
            // Disable button and show loading
            btn.disabled = true;
            btn.textContent = 'Loading...';
            display.style.display = 'none';
            
            // Make AJAX call
            fetch('<%= request.getContextPath() %>/kite-auth/get-login-url')
                .then(response => {
                    if (!response.ok) {
                        throw new Error('Network response was not ok');
                    }
                    return response.text();
                })
                .then(url => {
                    // Display the URL
                    if (url && url.trim() !== '') {
                        display.innerHTML = '<strong>Login URL:</strong><br><a href="' + url + '" target="_blank">' + url + '</a><br><small>(Click to open in new tab)</small>';
                        display.style.display = 'block';
                    } else {
                        display.innerHTML = '<span style="color: red;">No URL returned. Please check the controller configuration.</span>';
                        display.style.display = 'block';
                    }
                })
                .catch(error => {
                    display.innerHTML = '<span style="color: red;">Error: ' + error.message + '</span>';
                    display.style.display = 'block';
                })
                .finally(() => {
                    // Re-enable button
                    btn.disabled = false;
                    btn.textContent = 'Get Login URL';
                });
        }
    </script>
</body>
</html>