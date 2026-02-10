import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "*.ticket-queue.com",
      },
    ],
  },
};

export default nextConfig;
