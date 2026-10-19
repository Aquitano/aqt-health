import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Self-contained server bundle for the production Docker image.
  output: "standalone",
  poweredByHeader: false,
  headers: async () => [
    {
      source: "/:path*",
      headers: [
        { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
        { key: "X-Frame-Options", value: "DENY" },
      ],
    },
  ],
};

export default nextConfig;
