(function () {
    function buildOption(small, medium, large, xName, xLabel) {
        return {
            tooltip: {
                trigger: 'item',
                formatter: function (p) {
                    var html = p.seriesName
                        + '<br/>' + xLabel + ': ' + p.value[0].toFixed(2) + ' min'
                        + '<br/>Duration: ' + p.value[1].toFixed(2) + ' min';
                    // value[2] = estimated_duration_vmgr in minutes; absent
                    // (undefined) for builds saved before this field was added.
                    if (p.value.length > 2 && typeof p.value[2] === 'number') {
                        html += '<br/>Estimated Duration: ' + p.value[2].toFixed(2) + ' min';
                    }
                    return html;
                }
            },
            legend: {
                data: ['Small Duration (bottom 33%)',
                       'Medium Duration (middle 33%)',
                       'Large Duration (top 33%)'],
                top: 10
            },
            grid: { top: 60, left: '3%', right: '4%', bottom: 60, containLabel: true },
            xAxis: {
                type: 'value',
                name: xName,
                nameLocation: 'middle',
                nameGap: 30,
                scale: true
            },
            yAxis: {
                type: 'value',
                name: 'Duration (minutes)',
                nameLocation: 'middle',
                nameGap: 45,
                scale: true
            },
            series: [
                { name: 'Small Duration (bottom 33%)',  type: 'scatter', data: small,  symbolSize: 8, itemStyle: { color: '#52c41a' } },
                { name: 'Medium Duration (middle 33%)', type: 'scatter', data: medium, symbolSize: 8, itemStyle: { color: '#fa8c16' } },
                { name: 'Large Duration (top 33%)',     type: 'scatter', data: large,  symbolSize: 8, itemStyle: { color: '#f5222d' } }
            ],
            toolbox: { feature: { saveAsImage: { title: 'Save' } } }
        };
    }

    function init() {
        if (typeof echarts === 'undefined') {
            console.error('[vManager Charts] echarts library not loaded.');
            return;
        }
        if (typeof vManagerBuildChartProxy === 'undefined') {
            console.error('[vManager Charts] Stapler proxy not bound.');
            return;
        }
        var domStart = document.getElementById('runsDurationStartChart');
        var domEnd   = document.getElementById('runsDurationEndChart');
        if (!domStart || !domEnd) return;
        var chartStart = echarts.init(domStart);
        var chartEnd   = echarts.init(domEnd);
        chartStart.showLoading();
        chartEnd.showLoading();

        vManagerBuildChartProxy.getRegressionOptimizationData(function (response) {
            try {
                var data = response.responseObject() || {};
                chartStart.hideLoading();
                chartEnd.hideLoading();

                if (data.error) {
                    var errBox = document.getElementById('regressionOptimizationError');
                    if (errBox) {
                        errBox.textContent = data.error;
                        errBox.style.display = 'block';
                    }
                    console.warn('[vManager Charts] runs-duration:', data.error);
                }

                chartStart.setOption(buildOption(
                    data.small     || [],
                    data.medium    || [],
                    data.large     || [],
                    'Time to start (minutes)',
                    'Time to start'));

                chartEnd.setOption(buildOption(
                    data.smallEnd  || [],
                    data.mediumEnd || [],
                    data.largeEnd  || [],
                    'Time to end (minutes)',
                    'Time to end'));
            } catch (e) {
                chartStart.hideLoading();
                chartEnd.hideLoading();
                console.error('[vManager Charts] runs-duration error:', e);
            }
        });

        window.addEventListener('resize', function () {
            chartStart.resize();
            chartEnd.resize();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
