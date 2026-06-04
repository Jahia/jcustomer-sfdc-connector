const path = require('path');
const { CleanWebpackPlugin } = require('clean-webpack-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');
const ModuleFederationPlugin = require('webpack/lib/container/ModuleFederationPlugin');
const getModuleFederationConfig = require('@jahia/webpack-config/getModuleFederationConfig');
const packageJson = require('./package.json');

module.exports = (env, argv) => ({
    entry: {
        main: path.resolve(__dirname, 'src/javascript/index')
    },
    output: {
        path: path.resolve(__dirname, 'src/main/resources/javascript/apps/'),
        filename: 'jahia.bundle.js',
        chunkFilename: '[name].jahia.[chunkhash:6].js'
    },
    resolve: {
        mainFields: ['module', 'main'],
        extensions: ['.mjs', '.js', '.jsx', '.json']
    },
    module: {
        rules: [
            { test: /\.m?js$/, type: 'javascript/auto', resolve: { fullySpecified: false } },
            {
                test: /\.jsx?$/,
                include: [path.join(__dirname, 'src')],
                use: {
                    loader: 'babel-loader',
                    options: {
                        presets: [
                            ['@babel/preset-env', { modules: false, targets: { safari: '7', ie: '10' } }],
                            '@babel/preset-react'
                        ],
                        plugins: ['@babel/plugin-syntax-dynamic-import']
                    }
                }
            },
            {
                test: /\.module\.css$/,
                use: ['style-loader', { loader: 'css-loader', options: { modules: true } }]
            },
            {
                test: /\.css$/,
                exclude: /\.module\.css$/,
                use: ['style-loader', 'css-loader']
            }
        ]
    },
    plugins: [
        new ModuleFederationPlugin(getModuleFederationConfig(packageJson)),
        new CleanWebpackPlugin({ verbose: false }),
        new CopyWebpackPlugin({ patterns: [{ from: './package.json', to: '' }] })
    ],
    mode: 'development',
    devtool: (argv?.mode === 'production') ? 'source-map' : 'eval-source-map'
});
